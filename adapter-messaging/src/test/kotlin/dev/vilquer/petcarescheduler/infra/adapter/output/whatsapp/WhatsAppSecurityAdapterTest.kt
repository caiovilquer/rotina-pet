package dev.vilquer.petcarescheduler.infra.adapter.output.whatsapp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WhatsAppSecurityAdapterTest {
    private val currentHmac = ByteArray(32) { 1 }
    private val previousHmac = ByteArray(32) { 2 }
    private val currentEncryption = ByteArray(32) { 3 }
    private val previousEncryption = ByteArray(32) { 4 }
    private val adapter = WhatsAppSecurityAdapter(
        WhatsAppSecurityMaterial(
            webhookVerifyToken = "verify-me",
            appSecret = "app-secret",
            currentHmacKey = currentHmac,
            previousHmacKey = previousHmac,
            currentEncryptionKey = currentEncryption,
            previousEncryptionKey = previousEncryption,
            currentKeyVersion = 2,
            previousKeyVersion = 1,
        ),
    )

    @Test
    fun `validates webhook signature over exact raw bytes`() {
        val body = "{\"entry\":[]}".toByteArray(StandardCharsets.UTF_8)
        val signature = "sha256=${hmac("app-secret".toByteArray(), body).toHex()}"

        assertTrue(adapter.verifySignature(body, signature))
        assertFalse(adapter.verifySignature("{ }".toByteArray(), signature))
        assertFalse(adapter.verifySignature(body, "sha256=00"))
    }

    @Test
    fun `encrypts sensitive values with authenticated context`() {
        val protected = adapter.protect("5511999999999", "identity:one")

        assertEquals("5511999999999", adapter.reveal(protected, "identity:one"))
        assertThrows(Exception::class.java) { adapter.reveal(protected, "identity:other") }
    }

    @Test
    fun `supports current and previous lookup keys during rotation`() {
        val lookups = adapter.lookupCandidates("123456", "5511999999999")

        assertEquals(2, lookups.size)
        assertNotEquals(lookups.first(), lookups.last())
        assertTrue(lookups.all { it.matches(Regex("^[a-f0-9]{64}$")) })
    }

    @Test
    fun `uses constant secret for challenge verification`() {
        assertTrue(adapter.verifyChallenge("verify-me"))
        assertFalse(adapter.verifyChallenge("verify-you"))
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
