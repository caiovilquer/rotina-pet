package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import com.openai.errors.OpenAIInvalidDataException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIRetryableException
import com.openai.errors.OpenAIServiceException
import com.openai.errors.RateLimitException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class OpenAiFailure(val code: String, val retryable: Boolean)

internal class OpenAiOutputException(message: String) : RuntimeException(message)

internal fun classifyOpenAiFailure(exception: RuntimeException): OpenAiFailure = when (exception) {
    is OpenAiOutputException, is OpenAIInvalidDataException -> OpenAiFailure("AI_OUTPUT_INVALID", false)
    is RateLimitException -> OpenAiFailure("AI_RATE_LIMITED", true)
    is OpenAIIoException, is OpenAIRetryableException -> OpenAiFailure("AI_PROVIDER_UNAVAILABLE", true)
    is OpenAIServiceException -> OpenAiFailure("AI_PROVIDER_UNAVAILABLE", exception.statusCode() >= 500)
    else -> OpenAiFailure("AI_OUTPUT_INVALID", false)
}

internal class SafetyIdentifier(private val key: String) {
    fun forContext(context: AiInvocationContext): String {
        if (key.isBlank()) return "disabled"
        val value = "v1|${context.householdId.value}|${context.actorTutorId?.value ?: "system"}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "rp1_" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }
}

internal fun resourceText(path: String): String = requireNotNull(
    OpenAiCareInstructionExtractorAdapter::class.java.classLoader.getResourceAsStream(path),
) { "Recurso de IA ausente: $path" }.bufferedReader().use { it.readText() }
