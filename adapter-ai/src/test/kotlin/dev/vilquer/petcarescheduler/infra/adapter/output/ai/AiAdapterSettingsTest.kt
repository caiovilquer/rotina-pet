package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class AiAdapterSettingsTest {
    @Test
    fun `rejects unknown provider instead of falling back to fake`() {
        assertThrows(IllegalArgumentException::class.java) { settings(provider = "other") }
    }

    @Test
    fun `requires api and hmac keys when openai capability is active`() {
        assertThrows(IllegalArgumentException::class.java) {
            settings(provider = "openai", enabled = true, apiKey = "", safetyKey = "")
        }
    }

    @Test
    fun `allows an openai deployment to start disabled before secrets are mounted`() {
        assertDoesNotThrow { settings(provider = "openai", enabled = false, apiKey = "", safetyKey = "") }
    }

    private fun settings(
        provider: String,
        enabled: Boolean = false,
        apiKey: String = "test-key",
        safetyKey: String = "test-safety-identifier-key-with-32-chars",
    ) = AiAdapterSettings(
        provider, enabled, false, apiKey, "https://api.openai.com/v1",
        "gpt-5.6-luna", "gpt-5.6-luna", "text-embedding-3-small",
        512, 64, "low", Duration.ofSeconds(20), 2, safetyKey,
    )
}
