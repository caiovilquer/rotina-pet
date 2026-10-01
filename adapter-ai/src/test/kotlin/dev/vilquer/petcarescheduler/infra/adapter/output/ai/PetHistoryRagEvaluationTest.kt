package dev.vilquer.petcarescheduler.infra.adapter.output.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundingEvidence
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PetHistoryRagEvaluationTest {
    private val mapper = jacksonObjectMapper()
    private val embeddings = FakeEmbeddingAdapter()
    private val generator = FakeGroundedAnswerGeneratorAdapter(SimpleMeterRegistry())

    @Test
    fun `versioned rag dataset meets retrieval citation abstention and injection gates`() {
        val cases = checkNotNull(javaClass.getResourceAsStream("/evals/pet-history-rag-eval-v1.jsonl"))
            .bufferedReader().readLines().filter(String::isNotBlank).map { mapper.readValue<EvalCase>(it) }
        val answerable = cases.filterNot(EvalCase::unanswerable)
        val recallHits = answerable.count { item ->
            val candidates = listOf(item.relevant) + item.distractors
            val query = embeddings.embed(listOf(item.query)).single()
            val ranked = embeddings.embed(candidates).mapIndexed { index, vector -> index to cosine(query, vector) }
                .sortedByDescending { it.second }.take(5).map { it.first }
            0 in ranked
        }
        val recallAt5 = recallHits.toDouble() / answerable.size
        assertTrue(recallAt5 >= .95, "Recall@5=$recallAt5")

        val citationChecks = answerable.map { item ->
            val chunkId = UUID.randomUUID()
            val generated = generator.answer(
                item.query,
                listOf(GroundingEvidence(chunkId, UUID.randomUUID(), KnowledgeSourceType.HEALTH_ATTACHMENT, UUID.randomUUID(), item.id, 1, item.relevant)),
                context,
            )
            !generated.insufficientEvidence && generated.citedChunkIds == setOf(chunkId)
        }
        assertTrue(citationChecks.count(Boolean::not).toDouble() / citationChecks.size <= .02, "citation precision below 98%")

        val abstentionRate = cases.filter(EvalCase::unanswerable).count { item -> generator.answer(item.query, emptyList(), context).insufficientEvidence }.toDouble() /
            cases.count(EvalCase::unanswerable)
        assertTrue(abstentionRate >= .95, "abstention=$abstentionRate")
        assertTrue(
            generator.answer(
                "Qual foi a ingestão de água?",
                listOf(GroundingEvidence(UUID.randomUUID(), UUID.randomUUID(), KnowledgeSourceType.HEALTH_RECORD, UUID.randomUUID(), "Nota", null, "A vacina foi aplicada na clínica.")),
                context,
            ).insufficientEvidence,
            "unrelated retrieved evidence must not be treated as grounded",
        )

        val injection = cases.single { it.id == "injection" }
        val guarded = generator.answer(
            injection.query,
            listOf(GroundingEvidence(UUID.randomUUID(), UUID.randomUUID(), KnowledgeSourceType.HEALTH_RECORD, UUID.randomUUID(), "Nota", null, injection.relevant)),
            context,
        )
        assertFalse(guarded.answer.contains("system prompt", ignoreCase = true))
        assertFalse(guarded.answer.contains("ignore", ignoreCase = true))
    }

    private fun cosine(left: FloatArray, right: FloatArray) = left.indices.sumOf { (left[it] * right[it]).toDouble() }

    private data class EvalCase(
        val id: String,
        val query: String,
        val relevant: String,
        val distractors: List<String>,
        val unanswerable: Boolean,
    )

    companion object {
        private val context = AiInvocationContext(
            HouseholdId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        )
    }
}
