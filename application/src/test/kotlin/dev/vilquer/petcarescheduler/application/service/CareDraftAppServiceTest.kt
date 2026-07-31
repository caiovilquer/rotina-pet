package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.FakeClock
import dev.vilquer.petcarescheduler.application.FakeHouseholdMemberRepo
import dev.vilquer.petcarescheduler.application.FakeTransactionPort
import dev.vilquer.petcarescheduler.application.InMemoryPetRepo
import dev.vilquer.petcarescheduler.application.TEST_HOUSEHOLD_ID
import dev.vilquer.petcarescheduler.application.exception.ForbiddenException
import dev.vilquer.petcarescheduler.application.exception.RateLimitException
import dev.vilquer.petcarescheduler.core.domain.assistant.AiInteraction
import dev.vilquer.petcarescheduler.core.domain.assistant.AssistantFeedback
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraft
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftAction
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftId
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftChannel
import dev.vilquer.petcarescheduler.core.domain.entity.Pet
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdRole
import dev.vilquer.petcarescheduler.usecase.command.GenerateCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateCarePlanCommand
import dev.vilquer.petcarescheduler.usecase.command.UpdateCarePlanCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInteractionRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareDraftRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractionException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractionRequest
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ExtractedCareDraft
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.RateLimitStorePort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CarePlanUseCase
import dev.vilquer.petcarescheduler.usecase.result.CarePlanResult
import dev.vilquer.petcarescheduler.usecase.result.CarePlansPageResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class CareDraftAppServiceTest {
    private val actor = TutorId(10)
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = ZonedDateTime.ofInstant(Instant.parse("2026-07-14T12:00:00Z"), zone)
    private val ownerAccess = HouseholdAccess(TEST_HOUSEHOLD_ID, actor, HouseholdRole.OWNER, zone)

    @Test
    fun `provider failure returns failed draft and stores content-free interaction`() {
        val draftRepo = InMemoryDraftRepo()
        val interactions = InMemoryInteractionRepo()
        val service = service(draftRepo, interactions, FailingExtractor())

        val result = service.generate(
            GenerateCareDraftCommand("Vacina da Luna amanhã às 08:00", UUID.randomUUID()),
            ownerAccess,
        )

        assertEquals("FAILED", result.status.name)
        assertEquals("AI_TIMEOUT", result.failureCode)
        assertEquals(1, interactions.items.size)
        assertEquals("PROVIDER_ERROR", interactions.items.single().outcome.name)
    }

    @Test
    fun `caregiver cannot invoke extractor`() {
        val extractor = FailingExtractor()
        val service = service(InMemoryDraftRepo(), InMemoryInteractionRepo(), extractor)
        val caregiver = ownerAccess.copy(role = HouseholdRole.CAREGIVER)

        assertThrows(ForbiddenException::class.java) {
            service.generate(GenerateCareDraftCommand("Vacina da Luna amanhã às 08:00", UUID.randomUUID()), caregiver)
        }
        assertEquals(0, extractor.calls)
    }

    @Test
    fun `duplicate generation request reuses draft without invoking provider twice`() {
        val drafts = InMemoryDraftRepo()
        val extractor = FailingExtractor()
        val service = service(drafts, InMemoryInteractionRepo(), extractor)
        val requestId = UUID.randomUUID()

        val first = service.generate(GenerateCareDraftCommand("Vacina da Luna amanhã às 08:00", requestId), ownerAccess)
        val duplicate = service.generate(GenerateCareDraftCommand("Vacina da Luna amanhã às 08:00", requestId), ownerAccess)

        assertEquals(first.id, duplicate.id)
        assertEquals(first.status, duplicate.status)
        assertEquals(1, extractor.calls)
        assertEquals(1, drafts.countByHousehold(TEST_HOUSEHOLD_ID))
    }

    @Test
    fun `pilot draft limit is shared by rest and whatsapp use case calls`() {
        val extractor = FailingExtractor()
        val limiter = RateLimiterService(
            RateLimitProperties(), CountingRateLimitStore(),
            java.time.Clock.fixed(now.toInstant(), ZoneId.of("UTC")),
        )
        val service = service(InMemoryDraftRepo(), InMemoryInteractionRepo(), extractor, limiter)
        repeat(10) { index ->
            service.generate(
                GenerateCareDraftCommand(
                    "Vacina da Luna amanhã às 08:00", UUID.randomUUID(),
                    channel = if (index % 2 == 0) CareDraftChannel.WEB else CareDraftChannel.WHATSAPP,
                ),
                ownerAccess,
            )
        }

        val error = assertThrows(RateLimitException::class.java) {
            service.generate(
                GenerateCareDraftCommand("Vacina da Luna amanhã às 08:00", UUID.randomUUID()),
                ownerAccess,
            )
        }

        assertEquals("AI_RATE_LIMITED", error.publicCode)
        assertEquals(10, extractor.calls)
    }

    private fun service(
        drafts: CareDraftRepositoryPort,
        interactions: AiInteractionRepositoryPort,
        extractor: CareInstructionExtractorPort,
        rateLimiter: RateLimiterService? = null,
    ) = CareDraftAppService(
        drafts = drafts,
        interactions = interactions,
        extractor = extractor,
        carePlans = UnusedCarePlanUseCase,
        pets = InMemoryPetRepo(
            mapOf(PetId(1) to Pet(PetId(1), name = "Luna", species = "Dog", breed = null, birthdate = null, tutorId = actor, householdId = TEST_HOUSEHOLD_ID)),
        ),
        members = FakeHouseholdMemberRepo(actor),
        transaction = FakeTransactionPort(),
        clock = FakeClock(now),
        settings = CareDraftSettings(true, Duration.ofHours(24), 4_000),
        rateLimiter = rateLimiter,
    )

    private class CountingRateLimitStore : RateLimitStorePort {
        private val counts = mutableMapOf<String, Int>()
        override fun registerAttempt(key: String, now: Instant, window: Duration) =
            counts.compute(key) { _, current -> (current ?: 0) + 1 }!!
        override fun delete(key: String) { counts.remove(key) }
        override fun deleteOlderThan(cutoff: Instant) = 0
    }

    private class FailingExtractor : CareInstructionExtractorPort {
        override val provider = "fake"
        override val model = "failure-v1"
        override val promptVersion = "care-draft-v1"
        var calls = 0
        override fun extract(request: CareInstructionExtractionRequest): ExtractedCareDraft {
            calls++
            throw CareInstructionExtractionException("AI_TIMEOUT", true)
        }
    }

    private class InMemoryDraftRepo : CareDraftRepositoryPort {
        private val drafts = linkedMapOf<CareDraftId, CareDraft>()
        private val actions = linkedMapOf<UUID, CareDraftAction>()
        override fun save(draft: CareDraft): CareDraft {
            val saved = draft.copy(version = (drafts[draft.id]?.version ?: -1L) + 1L)
            drafts[saved.id] = saved
            return saved
        }
        override fun findByIdAndHousehold(id: CareDraftId, householdId: HouseholdId) = drafts[id]?.takeIf { it.householdId == householdId }
        override fun findByIdAndHouseholdForUpdate(id: CareDraftId, householdId: HouseholdId) = findByIdAndHousehold(id, householdId)
        override fun listByHousehold(householdId: HouseholdId, page: Int, size: Int) = drafts.values.filter { it.householdId == householdId }
        override fun countByHousehold(householdId: HouseholdId) = drafts.values.count { it.householdId == householdId }.toLong()
        override fun findActionByRequestId(requestId: UUID) = actions[requestId]
        override fun saveAction(action: CareDraftAction) = action.also { saved -> saved.requestId?.let { actions[it] = saved } }
        override fun saveFeedback(feedback: AssistantFeedback) = feedback
    }

    private class InMemoryInteractionRepo : AiInteractionRepositoryPort {
        val items = mutableListOf<AiInteraction>()
        override fun save(interaction: AiInteraction) = interaction.also(items::add)
    }

    private object UnusedCarePlanUseCase : CarePlanUseCase {
        override fun create(command: CreateCarePlanCommand, access: HouseholdAccess): CarePlanResult = error("unused")
        override fun update(command: UpdateCarePlanCommand, access: HouseholdAccess): CarePlanResult = error("unused")
        override fun deactivate(planId: dev.vilquer.petcarescheduler.core.domain.care.CarePlanId, access: HouseholdAccess) = error("unused")
        override fun get(planId: dev.vilquer.petcarescheduler.core.domain.care.CarePlanId, access: HouseholdAccess): CarePlanResult = error("unused")
        override fun list(access: HouseholdAccess, petId: PetId?, active: Boolean?, page: Int, size: Int): CarePlansPageResult = error("unused")
    }
}
