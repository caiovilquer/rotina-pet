package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.ForbiddenException
import dev.vilquer.petcarescheduler.application.exception.NotFoundException
import dev.vilquer.petcarescheduler.core.domain.assistant.AiInteraction
import dev.vilquer.petcarescheduler.core.domain.assistant.AiInteractionOutcome
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftChannel
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdPermission
import dev.vilquer.petcarescheduler.usecase.command.AddAssistantAnswerFeedbackCommand
import dev.vilquer.petcarescheduler.usecase.command.AskPetHistoryQuestionCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInteractionRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerAudit
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerFeedback
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerKind
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ClockPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EmbeddingPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundedAnswerGeneratorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundingEvidence
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundedAnswerGenerationException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EmbeddingException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.SemanticSearchPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.SemanticSearchRequest
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TransactionPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PetRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.PetHistoryAssistantUseCase
import dev.vilquer.petcarescheduler.usecase.result.AssistantCitationResult
import dev.vilquer.petcarescheduler.usecase.result.PetHistoryAnswerResult
import java.time.Instant
import java.util.UUID

data class PetHistoryAssistantSettings(
    val ragEnabled: Boolean = false,
    val maxQuestionCharacters: Int = 1_000,
    val retrievalLimit: Int = 5,
    val minimumSemanticScore: Double = 0.08,
) {
    init {
        require(maxQuestionCharacters in 100..4_000) { "assistant_question_limit_invalid" }
        require(retrievalLimit in 1..10) { "assistant_retrieval_limit_invalid" }
        require(minimumSemanticScore in -1.0..1.0) { "assistant_semantic_score_invalid" }
    }
}

class PetHistoryAssistantService(
    private val catalog: StructuredPetHistoryCatalog,
    private val pets: PetRepositoryPort,
    private val embeddings: EmbeddingPort,
    private val search: SemanticSearchPort,
    private val generator: GroundedAnswerGeneratorPort,
    private val answers: AssistantAnswerRepositoryPort,
    private val interactions: AiInteractionRepositoryPort,
    private val transaction: TransactionPort,
    private val clock: ClockPort,
    private val settings: PetHistoryAssistantSettings,
    private val rateLimiter: RateLimiterService? = null,
) : PetHistoryAssistantUseCase {

    override fun ask(command: AskPetHistoryQuestionCommand, access: HouseholdAccess): PetHistoryAnswerResult {
        requireView(access)
        if (!pets.existsForHousehold(command.petId, access.householdId)) throw NotFoundException("Pet não encontrado")
        val question = command.question.trim()
        require(question.isNotEmpty() && question.length <= settings.maxQuestionCharacters) { "assistant_question_invalid" }
        rateLimiter?.check(
            RateLimitAction.ASSISTANT_QUESTION,
            "${access.householdId.value}:${access.actorTutorId.value}",
        )
        val now = clock.now(access.zoneId).toInstant()
        val intent = catalog.classify(question)
        if (intent != PetHistoryIntent.DOCUMENT_SEARCH) {
            val result = catalog.execute(intent, command.petId, access, now)
            val kind = if (intent == PetHistoryIntent.CLINICAL_REQUEST) AssistantAnswerKind.REFUSAL else AssistantAnswerKind.STRUCTURED
            return persist(
                access, command, kind, result.answer, result.citations, result.insufficientEvidence,
                result.suggestedFollowUps, now, null, null, null,
            )
        }
        if (!settings.ragEnabled) {
            return persist(
                access, command, AssistantAnswerKind.RAG,
                "A pesquisa em notas e documentos está temporariamente indisponível. As consultas de agenda e medições continuam funcionando.",
                emptyList(), true, listOf("Tente novamente mais tarde."), now, null, null, null,
            )
        }
        return answerFromKnowledge(command, access, question, now)
    }

    override fun addFeedback(command: AddAssistantAnswerFeedbackCommand, access: HouseholdAccess) {
        requireView(access)
        require(command.reason == null || command.reason.matches(Regex("^[A-Z0-9_]{3,80}$"))) { "assistant_feedback_reason_invalid" }
        require(command.comment == null || command.comment.length <= 1_000) { "assistant_feedback_comment_invalid" }
        transaction.execute {
            answers.findAnswerByIdAndHousehold(command.answerId, access.householdId)
                ?: throw NotFoundException("Resposta não encontrada")
            answers.saveFeedback(
                AssistantAnswerFeedback(
                    answerId = command.answerId,
                    householdId = access.householdId,
                    actorTutorId = access.actorTutorId,
                    positive = command.positive,
                    reason = command.reason,
                    comment = command.comment?.trim()?.takeIf(String::isNotEmpty),
                    createdAt = clock.now(access.zoneId).toInstant(),
                ),
            )
        }
    }

    private fun answerFromKnowledge(
        command: AskPetHistoryQuestionCommand,
        access: HouseholdAccess,
        question: String,
        now: Instant,
    ): PetHistoryAnswerResult {
        val startedAt = System.nanoTime()
        return try {
            val context = AiInvocationContext(access.householdId, access.actorTutorId)
            val vector = embeddings.embed(listOf(question), context).single()
            require(vector.size == embeddings.dimension) { "embedding_dimension_invalid" }
            val retrieved = search.search(
                SemanticSearchRequest(
                    access.householdId, command.petId, question, vector,
                    settings.retrievalLimit, settings.minimumSemanticScore,
                ),
            )
            if (retrieved.isEmpty()) {
                saveInteraction(access, elapsedMillis(startedAt), AiInteractionOutcome.SUCCESS, null, now, null, null)
                return persist(
                    access, command, AssistantAnswerKind.RAG,
                    "Não encontrei informação suficiente nas notas e documentos disponíveis para responder com segurança.",
                    emptyList(), true, listOf("Confira se o documento já está disponível para pesquisa."), now,
                    generator.provider, generator.model, generator.promptVersion,
                )
            }
            val evidence = retrieved.map {
                GroundingEvidence(it.chunkId, it.sourceId, it.sourceType, it.resourceId, it.title, it.page, it.text.take(800))
            }
            val generated = generator.answer(question, evidence, context)
            val allowed = evidence.associateBy { it.chunkId }
            if (!allowed.keys.containsAll(generated.citedChunkIds) || (!generated.insufficientEvidence && generated.citedChunkIds.isEmpty())) {
                throw IllegalArgumentException("grounded_answer_citations_invalid")
            }
            val citations = generated.citedChunkIds.mapNotNull(allowed::get).map(::citation)
            saveInteraction(
                access, elapsedMillis(startedAt), AiInteractionOutcome.SUCCESS, null, now,
                generated.inputTokens, generated.outputTokens,
            )
            persist(
                access, command, AssistantAnswerKind.RAG, generated.answer, citations,
                generated.insufficientEvidence, emptyList(), now,
                generator.provider, generator.model, generator.promptVersion,
            )
        } catch (exception: RuntimeException) {
            saveInteraction(access, elapsedMillis(startedAt), AiInteractionOutcome.PROVIDER_ERROR, normalizeError(exception), now, null, null)
            persist(
                access, command, AssistantAnswerKind.RAG,
                "Não foi possível consultar notas e documentos agora. Nenhuma informação foi inferida.",
                emptyList(), true, listOf("Tente novamente mais tarde."), now,
                generator.provider, generator.model, generator.promptVersion,
            )
        }
    }

    private fun persist(
        access: HouseholdAccess,
        command: AskPetHistoryQuestionCommand,
        kind: AssistantAnswerKind,
        text: String,
        citations: List<AssistantCitationResult>,
        insufficient: Boolean,
        followUps: List<String>,
        at: Instant,
        provider: String?,
        model: String?,
        promptVersion: String?,
    ): PetHistoryAnswerResult {
        val id = UUID.randomUUID()
        transaction.execute {
            answers.save(
                AssistantAnswerAudit(
                    id, access.householdId, access.actorTutorId, command.petId, kind,
                    KnowledgeSourceFactory.sha256(command.question.trim()), insufficient, citations.size,
                    provider, model, promptVersion, CORPUS_VERSION, at,
                ),
            )
        }
        return PetHistoryAnswerResult(id, kind, text, citations, insufficient, followUps, at)
    }

    private fun citation(item: GroundingEvidence) = AssistantCitationResult(
        sourceType = item.sourceType,
        sourceId = item.sourceId,
        resourceId = item.resourceId,
        title = item.title,
        page = item.page,
        excerpt = item.excerpt.take(500),
        contentUrl = if (item.sourceType.name == "HEALTH_ATTACHMENT") {
            "/api/v1/health-attachments/${item.resourceId}/download-url"
        } else null,
    )

    private fun saveInteraction(
        access: HouseholdAccess,
        latency: Long,
        outcome: AiInteractionOutcome,
        errorCode: String?,
        at: Instant,
        inputTokens: Int?,
        outputTokens: Int?,
    ) = transaction.execute {
        interactions.save(
            AiInteraction(
                draftId = null,
                householdId = access.householdId,
                actorTutorId = access.actorTutorId,
                operation = "GROUND_HISTORY_ANSWER",
                channel = CareDraftChannel.WEB,
                provider = generator.provider,
                model = generator.model,
                promptVersion = generator.promptVersion,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                latencyMillis = latency,
                outcome = outcome,
                errorCode = errorCode,
                createdAt = at,
            ),
        )
    }

    private fun requireView(access: HouseholdAccess) {
        if (!access.can(HouseholdPermission.VIEW)) throw ForbiddenException("Seu papel nesta família não permite consultar o histórico")
    }

    private fun normalizeError(exception: RuntimeException): String = when (exception) {
        is GroundedAnswerGenerationException -> exception.code
        is EmbeddingException -> exception.code
        is IllegalArgumentException -> "RAG_INVALID_OUTPUT"
        else -> "RAG_PROVIDER_ERROR"
    }

    private fun elapsedMillis(startedAt: Long) = (System.nanoTime() - startedAt).coerceAtLeast(0) / 1_000_000

    companion object {
        private const val CORPUS_VERSION = "knowledge-v1"
    }
}
