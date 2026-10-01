package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import com.openai.client.OpenAIClient
import com.openai.models.embeddings.EmbeddingCreateParams
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EmbeddingException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EmbeddingPort
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

class OpenAiEmbeddingAdapter(
    private val client: OpenAIClient,
    private val settings: AiAdapterSettings,
    private val meterRegistry: MeterRegistry,
) : EmbeddingPort {
    override val provider = "openai"
    override val model = settings.embeddingModel
    override val dimension = settings.embeddingDimensions
    private val safetyIdentifier = SafetyIdentifier(settings.safetyIdentifierKey)

    override fun embed(texts: List<String>, context: AiInvocationContext?): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        require(texts.all { it.isNotBlank() }) { "embedding_input_invalid" }
        val sample = Timer.start(meterRegistry)
        return try {
            texts.chunked(settings.embeddingBatchSize).flatMap { batch ->
                val builder = EmbeddingCreateParams.builder()
                    .inputOfArrayOfStrings(batch)
                    .model(model)
                    .dimensions(dimension.toLong())
                context?.let { builder.user(safetyIdentifier.forContext(it)) }
                val response = client.embeddings().create(builder.build())
                val ordered = response.data().sortedBy { it.index() }
                if (ordered.size != batch.size || ordered.any { it.embedding().size != dimension }) {
                    throw OpenAiOutputException("embedding_output_invalid")
                }
                ordered.map { item -> item.embedding().toFloatArray() }
            }.also {
                meterRegistry.counter("rotinapet.ai.requests", "operation", "embedding", "provider", provider, "outcome", "success").increment()
            }
        } catch (exception: RuntimeException) {
            meterRegistry.counter("rotinapet.ai.requests", "operation", "embedding", "provider", provider, "outcome", "error").increment()
            val failure = classifyOpenAiFailure(exception)
            throw EmbeddingException(failure.code, failure.retryable, exception)
        } finally {
            sample.stop(meterRegistry.timer("rotinapet.ai.latency", "operation", "embedding", "provider", provider, "model", model))
        }
    }
}
