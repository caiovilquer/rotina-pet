package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EmbeddingPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundedAnswerGeneratorPort
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.net.URI
import java.time.Duration

data class AiAdapterSettings(
    val provider: String,
    val aiEnabled: Boolean,
    val ragEnabled: Boolean,
    val apiKey: String,
    val baseUrl: String,
    val careDraftModel: String,
    val groundedAnswerModel: String,
    val embeddingModel: String,
    val embeddingDimensions: Int,
    val embeddingBatchSize: Int,
    val reasoningEffort: String,
    val timeout: Duration,
    val maxRetries: Int,
    val safetyIdentifierKey: String,
) {
    init {
        require(provider in setOf("fake", "openai")) { "AI_PROVIDER deve ser fake ou openai" }
        require(embeddingDimensions == 512) { "AI_EMBEDDING_DIMENSIONS deve ser 512 nesta onda" }
        require(embeddingBatchSize in 1..2_048) { "AI_EMBEDDING_BATCH_SIZE inválido" }
        require(reasoningEffort in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) {
            "AI_OPENAI_REASONING_EFFORT inválido"
        }
        require(!timeout.isNegative && !timeout.isZero) { "AI_OPENAI_TIMEOUT inválido" }
        require(maxRetries in 0..2) { "AI_OPENAI_MAX_RETRIES deve estar entre 0 e 2" }
        if (provider == "openai") {
            require(runCatching { URI(baseUrl) }.getOrNull()?.scheme in setOf("http", "https")) { "OPENAI_BASE_URL inválida" }
            require(careDraftModel.isNotBlank() && groundedAnswerModel.isNotBlank() && embeddingModel.isNotBlank()) {
                "Modelos OpenAI devem ser configurados"
            }
            if (aiEnabled || ragEnabled) {
                require(apiKey.isNotBlank()) { "OPENAI_API_KEY é obrigatória quando a IA OpenAI está ativa" }
                require(safetyIdentifierKey.length >= 32) {
                    "AI_SAFETY_IDENTIFIER_KEY deve ter pelo menos 32 caracteres quando a IA OpenAI está ativa"
                }
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
class AiAdapterConfiguration {
    @Bean
    fun aiAdapterSettings(environment: Environment) = AiAdapterSettings(
        provider = environment.getProperty("app.ai.provider", "fake").trim().lowercase(),
        aiEnabled = environment.getProperty("app.ai.enabled", Boolean::class.java, false),
        ragEnabled = environment.getProperty("app.ai.rag-enabled", Boolean::class.java, false),
        apiKey = environment.getProperty("app.ai.openai-api-key", "").trim(),
        baseUrl = environment.getProperty("app.ai.openai-base-url", "https://api.openai.com/v1").trimEnd('/'),
        careDraftModel = environment.getProperty("app.ai.care-draft-model", "gpt-5.6-luna").trim(),
        groundedAnswerModel = environment.getProperty("app.ai.grounded-answer-model", "gpt-5.6-luna").trim(),
        embeddingModel = environment.getProperty("app.ai.embedding-model", "text-embedding-3-small").trim(),
        embeddingDimensions = environment.getProperty("app.ai.embedding-dimensions", Int::class.java, 512),
        embeddingBatchSize = environment.getProperty("app.ai.embedding-batch-size", Int::class.java, 64),
        reasoningEffort = environment.getProperty("app.ai.openai-reasoning-effort", "low").trim().lowercase(),
        timeout = environment.getProperty("app.ai.openai-timeout", Duration::class.java, Duration.ofSeconds(20)),
        maxRetries = environment.getProperty("app.ai.openai-max-retries", Int::class.java, 2),
        safetyIdentifierKey = environment.getProperty("app.ai.safety-identifier-key", ""),
    )

    @Bean
    @ConditionalOnProperty(name = ["app.ai.provider"], havingValue = "openai")
    fun openAiClient(settings: AiAdapterSettings): OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey(settings.apiKey.ifBlank { "disabled-openai-key" })
        .baseUrl(settings.baseUrl)
        .timeout(settings.timeout)
        .maxRetries(settings.maxRetries)
        .build()

    @Bean
    @ConditionalOnProperty(name = ["app.ai.provider"], havingValue = "fake", matchIfMissing = true)
    fun fakeCareInstructionExtractor(meterRegistry: MeterRegistry): CareInstructionExtractorPort =
        FakeCareInstructionExtractorAdapter(meterRegistry)

    @Bean
    @ConditionalOnProperty(name = ["app.ai.provider"], havingValue = "fake", matchIfMissing = true)
    fun fakeEmbedding(): EmbeddingPort = FakeEmbeddingAdapter()

    @Bean
    @ConditionalOnProperty(name = ["app.ai.provider"], havingValue = "fake", matchIfMissing = true)
    fun fakeGroundedAnswerGenerator(meterRegistry: MeterRegistry): GroundedAnswerGeneratorPort =
        FakeGroundedAnswerGeneratorAdapter(meterRegistry)

    @Bean
    @ConditionalOnProperty(name = ["app.ai.provider"], havingValue = "openai")
    fun openAiCareInstructionExtractor(
        client: OpenAIClient,
        settings: AiAdapterSettings,
        meterRegistry: MeterRegistry,
        objectMapper: ObjectMapper,
    ): CareInstructionExtractorPort = OpenAiCareInstructionExtractorAdapter(client, settings, meterRegistry, objectMapper)

    @Bean
    @ConditionalOnProperty(name = ["app.ai.provider"], havingValue = "openai")
    fun openAiEmbedding(
        client: OpenAIClient,
        settings: AiAdapterSettings,
        meterRegistry: MeterRegistry,
    ): EmbeddingPort = OpenAiEmbeddingAdapter(client, settings, meterRegistry)

    @Bean
    @ConditionalOnProperty(name = ["app.ai.provider"], havingValue = "openai")
    fun openAiGroundedAnswerGenerator(
        client: OpenAIClient,
        settings: AiAdapterSettings,
        meterRegistry: MeterRegistry,
        objectMapper: ObjectMapper,
    ): GroundedAnswerGeneratorPort = OpenAiGroundedAnswerGeneratorAdapter(client, settings, meterRegistry, objectMapper)
}
