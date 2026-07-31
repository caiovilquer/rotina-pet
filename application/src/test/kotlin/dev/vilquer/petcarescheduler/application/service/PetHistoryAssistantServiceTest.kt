package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.FakeClock
import dev.vilquer.petcarescheduler.application.FakeTransactionPort
import dev.vilquer.petcarescheduler.application.InMemoryCareOccurrenceRepo
import dev.vilquer.petcarescheduler.application.InMemoryHealthMeasurementRepo
import dev.vilquer.petcarescheduler.application.InMemoryHealthRecordRepo
import dev.vilquer.petcarescheduler.application.InMemoryPetRepo
import dev.vilquer.petcarescheduler.core.domain.assistant.AiInteraction
import dev.vilquer.petcarescheduler.core.domain.entity.Pet
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.health.HealthRecord
import dev.vilquer.petcarescheduler.core.domain.health.HealthRecordType
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdRole
import dev.vilquer.petcarescheduler.usecase.command.AskPetHistoryQuestionCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInteractionRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInvocationContext
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerAudit
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerFeedback
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EmbeddingPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GeneratedGroundedAnswer
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundedAnswerGeneratorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundingEvidence
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceType
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.RetrievedKnowledge
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.SemanticSearchPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.SemanticSearchRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class PetHistoryAssistantServiceTest {
    private val household = HouseholdId(UUID.fromString("00000000-0000-0000-0000-000000000031"))
    private val otherHousehold = HouseholdId(UUID.fromString("00000000-0000-0000-0000-000000000032"))
    private val actor = TutorId(1)
    private val petId = PetId(31)
    private val now = Instant.parse("2026-07-14T15:00:00Z")
    private val access = HouseholdAccess(household, actor, HouseholdRole.VIEWER, ZoneId.of("America/Sao_Paulo"))
    private lateinit var records: InMemoryHealthRecordRepo
    private lateinit var search: RecordingSearch
    private lateinit var service: PetHistoryAssistantService

    @BeforeEach
    fun setUp() {
        records = InMemoryHealthRecordRepo()
        records.save(
            HealthRecord(
                householdId = household, tutorId = actor, petId = petId, type = HealthRecordType.VACCINE,
                occurredAt = now.minusSeconds(86_400), title = "Vacina antirrábica", productName = "Produto registrado",
                createdByTutorId = actor, createdAt = now, updatedAt = now,
            ),
        )
        search = RecordingSearch()
        val pets = InMemoryPetRepo(mapOf(petId to Pet(petId, name = "Luna", species = "Cat", breed = null, birthdate = null, tutorId = actor, householdId = household)))
        service = PetHistoryAssistantService(
            StructuredPetHistoryCatalog(records, InMemoryHealthMeasurementRepo(), InMemoryCareOccurrenceRepo()),
            pets, TestEmbeddings(), search, TestGenerator(), InMemoryAnswers(), InMemoryInteractions(),
            FakeTransactionPort(), FakeClock(ZonedDateTime.parse("2026-07-14T12:00:00-03:00")),
            PetHistoryAssistantSettings(ragEnabled = true),
        )
    }

    @Test
    fun `structured vaccine question uses deterministic repository without retrieval`() {
        val answer = service.ask(AskPetHistoryQuestionCommand(petId, "Quando foi a última vacina?"), access)

        assertEquals("STRUCTURED", answer.kind.name)
        assertTrue(answer.answer.contains("13/07/2026"))
        assertEquals(1, answer.citations.size)
        assertFalse(search.called)
    }

    @Test
    fun `document question returns only generator citations from authorized retrieval`() {
        val chunkId = UUID.randomUUID()
        val sourceId = UUID.randomUUID()
        search.results = listOf(
            RetrievedKnowledge(
                chunkId, sourceId, KnowledgeSourceType.HEALTH_ATTACHMENT, UUID.randomUUID(), "Hemograma.pdf",
                "Hemoglobina dentro da faixa registrada.", 2, 1.0, .9, .03,
            ),
        )

        val answer = service.ask(AskPetHistoryQuestionCommand(petId, "O que consta no hemograma anexado?"), access)

        assertEquals("RAG", answer.kind.name)
        assertFalse(answer.insufficientEvidence)
        assertEquals(sourceId, answer.citations.single().sourceId)
        assertEquals(2, answer.citations.single().page)
        assertTrue(search.called)
    }

    @Test
    fun `clinical request is refused before retrieval`() {
        val answer = service.ask(AskPetHistoryQuestionCommand(petId, "Qual remédio e qual dose devo dar?"), access)

        assertEquals("REFUSAL", answer.kind.name)
        assertTrue(answer.answer.contains("não faz diagnóstico"))
        assertFalse(search.called)
    }

    @Test
    fun `question about registered medication remains deterministic`() {
        val answer = service.ask(AskPetHistoryQuestionCommand(petId, "Qual medicamento foi registrado?"), access)

        assertEquals("STRUCTURED", answer.kind.name)
        assertTrue(answer.insufficientEvidence)
        assertFalse(search.called)
    }

    @Test
    fun `pet from another household is hidden before any retrieval`() {
        val otherAccess = HouseholdAccess(otherHousehold, TutorId(9), HouseholdRole.OWNER)

        assertThrows(RuntimeException::class.java) {
            service.ask(AskPetHistoryQuestionCommand(petId, "O que consta no exame?"), otherAccess)
        }
        assertFalse(search.called)
    }

    private class TestEmbeddings : EmbeddingPort {
        override val provider = "test"
        override val model = "test"
        override val dimension = 512
        override fun embed(texts: List<String>, context: AiInvocationContext?) =
            texts.map { FloatArray(512).also { vector -> vector[0] = 1f } }
    }

    private class RecordingSearch : SemanticSearchPort {
        var called = false
        var results = emptyList<RetrievedKnowledge>()
        override fun search(request: SemanticSearchRequest): List<RetrievedKnowledge> { called = true; return results }
    }

    private class TestGenerator : GroundedAnswerGeneratorPort {
        override val provider = "test"
        override val model = "test"
        override val promptVersion = "grounded-answer-test"
        override fun answer(question: String, evidence: List<GroundingEvidence>, context: AiInvocationContext) = GeneratedGroundedAnswer(
            "O documento registra hemoglobina dentro da faixa.", setOf(evidence.single().chunkId), false,
        )
    }

    private class InMemoryAnswers : AssistantAnswerRepositoryPort {
        private val values = linkedMapOf<UUID, AssistantAnswerAudit>()
        override fun save(answer: AssistantAnswerAudit) = answer.also { values[it.id] = it }
        override fun findAnswerByIdAndHousehold(id: UUID, householdId: HouseholdId) = values[id]?.takeIf { it.householdId == householdId }
        override fun saveFeedback(feedback: AssistantAnswerFeedback) = feedback
    }

    private class InMemoryInteractions : AiInteractionRepositoryPort {
        override fun save(interaction: AiInteraction) = interaction
    }
}
