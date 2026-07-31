package dev.vilquer.petcarescheduler.infra

import dev.vilquer.petcarescheduler.application.service.KnowledgeSourceFactory
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.infra.adapter.output.external.KnowledgePersistenceAdapter
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeChunk
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeIndexOperation
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSource
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceStatus
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.SemanticSearchRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ContextConfiguration
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [PersistenceTestApplication::class])
@Import(KnowledgePersistenceAdapter::class)
class KnowledgePersistenceIntegrationTest : AbstractPostgresIntegrationTest() {
    @Autowired lateinit var knowledge: KnowledgePersistenceAdapter
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `hybrid retrieval filters household and pet before ranking and revocation`() {
        val a = scope("A")
        val b = scope("B")
        val sourceA = source(a, "Hemograma Luna", "a".repeat(64))
        val sourceB = source(b, "Documento de outra família", "b".repeat(64))
        knowledge.prepare(sourceA)
        knowledge.prepare(sourceB)
        knowledge.replaceChunksAndMarkReady(sourceA, listOf(chunk(sourceA, "Hemoglobina dentro da faixa registrada", unitVector(0))), Instant.now())
        knowledge.replaceChunksAndMarkReady(sourceB, listOf(chunk(sourceB, "Hemoglobina de outro pet", unitVector(0))), Instant.now())

        val results = knowledge.search(SemanticSearchRequest(a.householdId, a.petId, "hemoglobina", unitVector(0), 5))

        assertEquals(1, results.size)
        assertEquals(sourceA.id, results.single().sourceId)
        assertTrue(results.none { it.sourceId == sourceB.id })

        knowledge.markDeleted(a.householdId, sourceA.type, sourceA.resourceId, Instant.now())
        assertTrue(knowledge.search(SemanticSearchRequest(a.householdId, a.petId, "hemoglobina", unitVector(0), 5)).isEmpty())
    }

    @Test
    fun `embedding chunker or extractor signature mismatch schedules reindex discovery`() {
        val scope = scope("configuration")
        val old = source(scope, "Nota antiga", "d".repeat(64))
        knowledge.prepare(old)

        val mismatches = knowledge.findConfigurationMismatches(
            "openai/text-embedding-3-small/512", "semantic-chunker-v1", "health-record-v1", "pdfbox-v1", 10,
        )

        assertEquals(listOf(old.id), mismatches.map { it.id })
        val current = old.copy(
            extractorVersion = "health-record-v1",
            chunkerVersion = "semantic-chunker-v1",
            embeddingModel = "openai/text-embedding-3-small/512",
        )
        knowledge.replaceChunksAndMarkReady(current, listOf(chunk(current, "Nota reindexada", unitVector(0))), Instant.now())
        assertTrue(
            knowledge.findConfigurationMismatches(
                current.embeddingModel, current.chunkerVersion, current.extractorVersion, "pdfbox-v1", 10,
            ).isEmpty(),
        )
    }

    @Test
    fun `outbox claim is idempotent while a job is processing`() {
        val scope = scope("claim")
        val source = source(scope, "Nota", "c".repeat(64))
        knowledge.prepare(source)
        val now = Instant.now()
        knowledge.enqueue(source.id, KnowledgeIndexOperation.UPSERT, "source:${source.id}", now)
        knowledge.enqueue(source.id, KnowledgeIndexOperation.UPSERT, "source:${source.id}", now)

        assertEquals(1, knowledge.claimBatch(10, now).size)
        assertTrue(knowledge.claimBatch(10, now.plusSeconds(1)).isEmpty())
    }

    private fun scope(label: String): Scope {
        val tutorId = jdbc.queryForObject(
            "insert into tutor(first_name, email, password_hash, password_changed_at) values (?, ?, 'hash', current_timestamp) returning id",
            Long::class.java,
            label,
            "knowledge-${UUID.randomUUID()}@example.com",
        )!!
        val householdId = HouseholdId(UUID.randomUUID())
        jdbc.update(
            "insert into household(id, name, created_by_tutor_id, created_at, updated_at, timezone) values (?, ?, ?, current_timestamp, current_timestamp, 'America/Sao_Paulo')",
            householdId.value, "Casa $label", tutorId,
        )
        val petId = PetId(jdbc.queryForObject(
            "insert into pet(name, species, tutor_id, household_id) values (?, 'cat', ?, ?) returning id",
            Long::class.java,
            "Pet $label", tutorId, householdId.value,
        )!!)
        return Scope(householdId, petId)
    }

    private fun source(scope: Scope, title: String, checksum: String): KnowledgeSource {
        val resourceId = UUID.randomUUID()
        val now = Instant.now()
        return KnowledgeSource(
            KnowledgeSourceFactory.stableId(scope.householdId.value, KnowledgeSourceType.HEALTH_RECORD, resourceId),
            scope.householdId, scope.petId, KnowledgeSourceType.HEALTH_RECORD, resourceId, "1", title, checksum,
            status = KnowledgeSourceStatus.PENDING, extractorVersion = "record-v1", chunkerVersion = "chunk-v1",
            embeddingModel = "fake/local-hash-embedding-v2/512", createdAt = now, updatedAt = now,
        )
    }

    private fun chunk(source: KnowledgeSource, text: String, vector: FloatArray) = KnowledgeChunk(
        UUID.randomUUID(), source.id, 0, text, vector, null, 0, text.length,
        KnowledgeSourceFactory.sha256(text), source.embeddingModel,
    )

    private fun unitVector(index: Int) = FloatArray(512).also { it[index] = 1f }
    private data class Scope(val householdId: HouseholdId, val petId: PetId)
}
