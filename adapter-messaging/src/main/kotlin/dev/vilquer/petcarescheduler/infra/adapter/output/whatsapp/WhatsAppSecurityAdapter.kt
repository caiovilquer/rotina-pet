package dev.vilquer.petcarescheduler.infra.adapter.output.whatsapp

import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ProtectedValue
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppCryptoPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppWebhookSecurityPort
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class WhatsAppSecurityMaterial(
    val webhookVerifyToken: String,
    val appSecret: String,
    val currentHmacKey: ByteArray?,
    val previousHmacKey: ByteArray?,
    val currentEncryptionKey: ByteArray?,
    val previousEncryptionKey: ByteArray?,
    val currentKeyVersion: Int,
    val previousKeyVersion: Int?,
) {
    init {
        require(currentKeyVersion > 0) { "whatsapp_key_version_invalid" }
        require((previousEncryptionKey == null) == (previousKeyVersion == null)) { "whatsapp_previous_key_pair_invalid" }
        require(previousKeyVersion == null || previousKeyVersion > 0) { "whatsapp_previous_key_version_invalid" }
        require(previousKeyVersion == null || previousKeyVersion != currentKeyVersion) { "whatsapp_key_versions_must_differ" }
    }

    fun validateEnabled() {
        require(webhookVerifyToken.isNotBlank()) { "whatsapp_verify_token_missing" }
        require(appSecret.isNotBlank()) { "whatsapp_app_secret_missing" }
        require(currentHmacKey != null) { "whatsapp_hmac_key_missing" }
        require(currentEncryptionKey != null) { "whatsapp_encryption_key_missing" }
    }
}

class WhatsAppSecurityAdapter(private val material: WhatsAppSecurityMaterial) : WhatsAppCryptoPort, WhatsAppWebhookSecurityPort {

    override fun verifyChallenge(candidateToken: String): Boolean = constantTime(
        material.webhookVerifyToken.toByteArray(StandardCharsets.UTF_8),
        candidateToken.toByteArray(StandardCharsets.UTF_8),
    ) && material.webhookVerifyToken.isNotBlank()

    override fun verifySignature(rawBody: ByteArray, signatureHeader: String?): Boolean {
        if (material.appSecret.isBlank() || signatureHeader == null || !signatureHeader.startsWith("sha256=")) return false
        val expected = hmac(material.appSecret.toByteArray(StandardCharsets.UTF_8), rawBody)
        val supplied = parseHex(signatureHeader.removePrefix("sha256=")) ?: return false
        return constantTime(expected, supplied)
    }

    override fun lookupCandidates(businessPhoneNumberId: String, canonicalWaId: String): Set<String> {
        val context = "whatsapp-wa-id:v1\u0000$businessPhoneNumberId\u0000$canonicalWaId".toByteArray(StandardCharsets.UTF_8)
        return buildSet {
            add(hmacHex(requireNotNull(material.currentHmacKey) { "whatsapp_hmac_key_missing" }, context))
            material.previousHmacKey?.let { add(hmacHex(it, context)) }
        }
    }

    override fun tokenHash(rawToken: String): String = MessageDigest.getInstance("SHA-256")
        .digest(rawToken.toByteArray(StandardCharsets.UTF_8)).toHex()

    override fun protect(plainText: String, aad: String): ProtectedValue {
        val key = requireNotNull(material.currentEncryptionKey) { "whatsapp_encryption_key_missing" }
        require(key.size == 32) { "whatsapp_encryption_key_invalid" }
        val nonce = ByteArray(12).also(SECURE_RANDOM::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        return ProtectedValue(
            ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)),
            nonce = nonce,
            keyVersion = material.currentKeyVersion,
        )
    }

    override fun reveal(value: ProtectedValue, aad: String): String {
        val key = when (value.keyVersion) {
            material.currentKeyVersion -> material.currentEncryptionKey
            material.previousKeyVersion -> material.previousEncryptionKey
            else -> null
        } ?: throw IllegalStateException("whatsapp_encryption_key_version_unknown")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, value.nonce))
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(value.ciphertext), StandardCharsets.UTF_8)
    }

    private fun hmacHex(key: ByteArray, value: ByteArray): String = hmac(key, value).toHex()

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }

    private fun constantTime(left: ByteArray, right: ByteArray): Boolean = MessageDigest.isEqual(left, right)
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun parseHex(value: String): ByteArray? {
        if (value.length != 64 || !value.matches(Regex("^[0-9a-fA-F]+$"))) return null
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private val SECURE_RANDOM = SecureRandom()

        fun decodeKey(value: String?): ByteArray? = value?.trim()?.takeIf(String::isNotEmpty)?.let {
            runCatching { Base64.getDecoder().decode(it) }
                .getOrElse { throw IllegalArgumentException("whatsapp_key_must_be_base64") }
                .also { key -> require(key.size == 32) { "whatsapp_key_must_be_256_bits" } }
        }
    }
}
