package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GeneratedGroundedAnswer
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundedAnswerGeneratorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundingEvidence
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.text.Normalizer

class FakeGroundedAnswerGeneratorAdapter(
    private val meterRegistry: MeterRegistry,
) : GroundedAnswerGeneratorPort {
    override val provider = "local-fake"
    override val model = "grounded-extractive-v1"
    override val promptVersion = "grounded-answer-v1"

    override fun answer(question: String, evidence: List<GroundingEvidence>, context: AiInvocationContext): GeneratedGroundedAnswer {
        val sample = Timer.start(meterRegistry)
        return try {
            val questionTerms = terms(question)
            val selected = evidence.mapNotNull { item ->
                val safe = item.excerpt.split(Regex("(?<=[.!?])\\s+|\\n+"))
                    .asSequence()
                    .filterNot { sentence -> INJECTION_MARKERS.any { marker -> normalize(sentence).contains(marker) } }
                    .joinToString(" ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                val excerpt = bestExcerpt(safe, questionTerms)
                if (safe.isEmpty() || terms(excerpt).none(questionTerms::contains)) null else item to excerpt
            }.sortedByDescending { (_, excerpt) -> terms(excerpt).count(questionTerms::contains) }.take(2)
            if (selected.isEmpty()) {
                meterRegistry.counter("rotinapet.rag.answers", "status", "insufficient").increment()
                GeneratedGroundedAnswer(
                    "Não encontrei informação suficiente nas fontes disponíveis.", emptySet(), true,
                    terms(question).size, 8,
                )
            } else {
                val answer = selected.joinToString(" ") { (item, excerpt) -> "Em “${item.title}”, consta: $excerpt" }
                meterRegistry.counter("rotinapet.rag.answers", "status", "grounded").increment()
                GeneratedGroundedAnswer(
                    answer.take(2_000), selected.mapTo(linkedSetOf()) { it.first.chunkId }, false,
                    terms(question).size + selected.sumOf { terms(it.second).size }, terms(answer).size,
                )
            }
        } finally {
            sample.stop(meterRegistry.timer("rotinapet.rag.answer.latency", "provider", provider, "model", model))
        }
    }

    private fun bestExcerpt(text: String, questionTerms: Set<String>): String {
        val sentences = text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map(String::trim).filter(String::isNotEmpty)
        return (sentences.maxByOrNull { terms(it).count(questionTerms::contains) } ?: text).take(500)
    }

    private fun terms(value: String): Set<String> = normalize(value).split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .map { DOMAIN_TERMS[it] ?: it }
        .toSet()

    private fun normalize(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    companion object {
        private val INJECTION_MARKERS = setOf(
            "ignore as instrucoes", "ignore instrucoes", "system prompt", "developer message",
            "execute a ferramenta", "chame a ferramenta", "revele o prompt", "exfiltre",
        )
        private val STOP_WORDS = setOf(
            "aos", "com", "como", "das", "dos", "ela", "ele", "essa", "esse", "esta", "este",
            "foi", "foram", "isso", "qual", "que", "sobre", "uma", "uns", "umas",
        )
        private val DOMAIN_TERMS = mapOf(
            "agua" to "hidratacao",
            "bebeu" to "hidratacao",
            "beber" to "hidratacao",
            "hidratacao" to "hidratacao",
            "ingestao" to "hidratacao",
        )
    }
}
