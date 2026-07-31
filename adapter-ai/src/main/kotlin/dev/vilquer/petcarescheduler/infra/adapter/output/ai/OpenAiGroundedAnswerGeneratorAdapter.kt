package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseStatus
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GeneratedGroundedAnswer
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundedAnswerGenerationException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundedAnswerGeneratorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundingEvidence
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

class OpenAiGroundedAnswerGeneratorAdapter(
    private val client: OpenAIClient,
    private val settings: AiAdapterSettings,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
) : GroundedAnswerGeneratorPort {
    override val provider = "openai"
    override val model = settings.groundedAnswerModel
    override val promptVersion = "grounded-answer-v2-openai"
    private val prompt = resourceText("prompts/grounded-answer-v2-openai.md")
    private val safetyIdentifier = SafetyIdentifier(settings.safetyIdentifierKey)

    override fun answer(
        question: String,
        evidence: List<GroundingEvidence>,
        context: AiInvocationContext,
    ): GeneratedGroundedAnswer {
        val sample = Timer.start(meterRegistry)
        return try {
            val references = evidence.mapIndexed { index, item -> "chunk-${index + 1}" to item }.toMap()
            val input = objectMapper.writeValueAsString(
                mapOf(
                    "question" to question,
                    "evidence" to references.map { (reference, item) ->
                        mapOf(
                            "reference" to reference,
                            "sourceType" to item.sourceType.name,
                            "title" to item.title,
                            "page" to item.page,
                            "excerpt" to item.excerpt,
                        )
                    },
                ),
            )
            val params = ResponseCreateParams.builder()
                .input(input)
                .instructions(prompt)
                .store(false)
                .reasoning(Reasoning.builder().effort(ReasoningEffort.of(settings.reasoningEffort)).build())
                .safetyIdentifier(safetyIdentifier.forContext(context))
                .maxOutputTokens(1_200)
                .text(OpenAiStructuredOutputs.GroundedAnswerOutput::class.java)
                .model(model)
                .build()
            val response = client.responses().create(params)
            if (response.status().orElse(null) != ResponseStatus.COMPLETED) {
                throw OpenAiOutputException("grounded_answer_incomplete")
            }
            val contents = response.output().flatMap { item ->
                item.message().map { message -> message.content() }.orElse(emptyList())
            }
            if (contents.any { it.refusal().isPresent }) throw OpenAiOutputException("grounded_answer_refused")
            val output = contents.mapNotNull { it.outputText().orElse(null) }.singleOrNull()
                ?: throw OpenAiOutputException("grounded_answer_missing")
            val answer = output.answer.trim()
            if (answer.isEmpty() || answer.length > 2_000) throw OpenAiOutputException("grounded_answer_text_invalid")
            val unknown = output.citedChunkReferences.filterNot(references::containsKey)
            if (unknown.isNotEmpty()) throw OpenAiOutputException("grounded_answer_citation_invalid")
            val cited = output.citedChunkReferences.mapTo(linkedSetOf()) { references.getValue(it).chunkId }
            if ((output.insufficientEvidence && cited.isNotEmpty()) || (!output.insufficientEvidence && cited.isEmpty())) {
                throw OpenAiOutputException("grounded_answer_abstention_invalid")
            }
            val usage = response.usage().orElse(null)
            GeneratedGroundedAnswer(
                answer = answer,
                citedChunkIds = cited,
                insufficientEvidence = output.insufficientEvidence,
                inputTokens = usage?.inputTokens()?.toInt(),
                outputTokens = usage?.outputTokens()?.toInt(),
            ).also {
                meterRegistry.counter("rotinapet.rag.answers", "provider", provider, "status", if (it.insufficientEvidence) "insufficient" else "grounded").increment()
            }
        } catch (exception: RuntimeException) {
            val failure = classifyOpenAiFailure(exception)
            throw GroundedAnswerGenerationException(failure.code, failure.retryable, exception)
        } finally {
            sample.stop(meterRegistry.timer("rotinapet.rag.answer.latency", "provider", provider, "model", model))
        }
    }
}
