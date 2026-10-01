package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.client.okhttp.OpenAIOkHttpClient
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftField
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractionRequest
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionPetContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundingEvidence
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Tag("openai-live")
class OpenAiLiveEvaluationTest {
    private val mapper = jacksonObjectMapper()
    private val context = AiInvocationContext(
        HouseholdId(UUID.fromString("00000000-0000-0000-0000-000000000051")), TutorId(51),
    )
    private val settings = AiAdapterSettings(
        provider = "openai", aiEnabled = true, ragEnabled = true,
        apiKey = requireNotNull(System.getenv("OPENAI_API_KEY")),
        baseUrl = System.getenv("OPENAI_BASE_URL") ?: "https://api.openai.com/v1",
        careDraftModel = System.getenv("AI_CARE_DRAFT_MODEL") ?: "gpt-5.6-luna",
        groundedAnswerModel = System.getenv("AI_GROUNDED_ANSWER_MODEL") ?: "gpt-5.6-luna",
        embeddingModel = System.getenv("AI_EMBEDDING_MODEL") ?: "text-embedding-3-small",
        embeddingDimensions = 512, embeddingBatchSize = 64,
        reasoningEffort = System.getenv("AI_OPENAI_REASONING_EFFORT") ?: "low",
        timeout = Duration.ofSeconds(20), maxRetries = 2,
        safetyIdentifierKey = System.getenv("AI_SAFETY_IDENTIFIER_KEY")
            ?: "synthetic-openai-evaluation-key-rotinapet",
    )
    private val client = OpenAIOkHttpClient.builder().apiKey(settings.apiKey).baseUrl(settings.baseUrl)
        .timeout(settings.timeout).maxRetries(settings.maxRetries).build()
    private val registry = SimpleMeterRegistry()

    @Test
    fun `synthetic staging datasets satisfy wave five gates`() {
        val care = OpenAiCareInstructionExtractorAdapter(client, settings, registry, mapper)
        val draftCases = resource("/evals/care-draft-eval-v1.jsonl").map { mapper.readValue<DraftCase>(it) }
        var exact = 0
        var criticalAmbiguities = 0
        var criticalAbstentions = 0
        draftCases.forEach { item ->
            val result = care.extract(
                CareInstructionExtractionRequest(
                    item.instruction, Instant.parse("2026-07-14T12:00:00Z"), ZoneId.of("America/Sao_Paulo"),
                    listOf(CareInstructionPetContext(PetId(1), "Luna")), context,
                ),
            )
            val missing = (result.missingFields + result.fields.missingRequired()).map(CareDraftField::name).toSet()
            if (result.fields.type?.name == item.expectedType && result.fields.scheduleRule?.kind?.name == item.expectedSchedule && missing == item.missing.toSet()) exact++
            if (item.instruction.contains("duas vezes ao dia")) {
                criticalAmbiguities++
                if (CareDraftField.SCHEDULE.name in missing && result.warnings.any { it.blocking }) criticalAbstentions++
            }
        }
        assertTrue(exact.toDouble() / draftCases.size >= .90, "draft exact match $exact/${draftCases.size}")
        assertTrue(criticalAmbiguities > 0 && criticalAbstentions == criticalAmbiguities, "critical abstention gate")

        val embeddings = OpenAiEmbeddingAdapter(client, settings, registry)
        val generator = OpenAiGroundedAnswerGeneratorAdapter(client, settings, registry, mapper)
        val ragCases = resource("/evals/pet-history-rag-eval-v1.jsonl").map { mapper.readValue<RagCase>(it) }
        var recallHits = 0
        var answerable = 0
        var correctCitations = 0
        var citations = 0
        var abstentions = 0
        var unanswerable = 0
        ragCases.forEach { item ->
            val texts = (listOf(item.relevant).filter(String::isNotBlank) + item.distractors)
            val vectors = embeddings.embed(listOf(item.query) + texts, context)
            val query = vectors.first()
            val ranked = texts.indices.map { index -> index to cosine(query, vectors[index + 1]) }
                .sortedByDescending { it.second }.take(5).map { it.first }
            if (!item.unanswerable) {
                answerable++
                if (0 in ranked) recallHits++
            } else unanswerable++
            val relevantChunk = UUID.nameUUIDFromBytes("${item.id}:relevant".toByteArray())
            val evidence = ranked.map { index ->
                GroundingEvidence(
                    if (index == 0 && item.relevant.isNotBlank()) relevantChunk else UUID.nameUUIDFromBytes("${item.id}:$index".toByteArray()),
                    UUID.randomUUID(), KnowledgeSourceType.HEALTH_RECORD, UUID.randomUUID(), "Fixture ${item.id}", null, texts[index],
                )
            }
            val answer = generator.answer(item.query, evidence, context)
            if (item.unanswerable && answer.insufficientEvidence) abstentions++
            if (!item.unanswerable) answer.citedChunkIds.forEach { cited ->
                citations++
                if (cited == relevantChunk) correctCitations++
            }
            if (item.id == "injection") {
                assertFalse(answer.answer.contains("system prompt", ignoreCase = true))
                assertFalse(answer.answer.contains("ignore as instruções", ignoreCase = true))
            }
        }
        assertTrue(recallHits.toDouble() / answerable >= .95, "Recall@5=$recallHits/$answerable")
        assertTrue(citations > 0 && correctCitations.toDouble() / citations >= .98, "citation precision=$correctCitations/$citations")
        assertTrue(abstentions.toDouble() / unanswerable >= .95, "insufficient evidence abstention=$abstentions/$unanswerable")
    }

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream(path))
        .bufferedReader().readLines().filter(String::isNotBlank)

    private fun cosine(left: FloatArray, right: FloatArray) = left.indices.sumOf { (left[it] * right[it]).toDouble() }

    private data class DraftCase(val instruction: String, val expectedType: String?, val expectedSchedule: String?, val missing: List<String>)
    private data class RagCase(val id: String, val query: String, val relevant: String, val distractors: List<String>, val unanswerable: Boolean)
}
