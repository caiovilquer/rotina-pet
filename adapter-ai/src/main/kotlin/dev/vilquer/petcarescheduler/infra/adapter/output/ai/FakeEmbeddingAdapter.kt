package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EmbeddingPort
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.sqrt

class FakeEmbeddingAdapter : EmbeddingPort {
    override val provider = "fake"
    override val model = "local-hash-embedding-v2"
    override val dimension = 512

    override fun embed(texts: List<String>, context: dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext?): List<FloatArray> =
        texts.map(::vector)

    private fun vector(text: String): FloatArray {
        val values = FloatArray(dimension)
        val tokens = tokenize(text)
        val features = tokens.map { it to 1f } +
            tokens.zipWithNext { left, right -> "$left\u0000$right" to BIGRAM_WEIGHT }
        features.forEach { (feature, weight) ->
            val hash = MessageDigest.getInstance("SHA-256").digest(feature.toByteArray())
            val value = ByteBuffer.wrap(hash).int
            val index = (value and Int.MAX_VALUE) % dimension
            // Feature hashing sem sinal preserva a sobreposição lexical no
            // baseline local. Colisões ainda acrescentam ruído, mas nunca
            // cancelam um termo compartilhado entre pergunta e evidência.
            values[index] += weight
        }
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) values.indices.forEach { values[it] /= norm }
        return values
    }

    private fun tokenize(value: String): List<String> = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 && it !in STOP_WORDS && it.none(Char::isDigit) }

    companion object {
        private const val BIGRAM_WEIGHT = 0.25f
        private val STOP_WORDS = setOf("de", "da", "do", "das", "dos", "em", "um", "uma", "e", "o", "a", "para", "por", "que")
    }
}
