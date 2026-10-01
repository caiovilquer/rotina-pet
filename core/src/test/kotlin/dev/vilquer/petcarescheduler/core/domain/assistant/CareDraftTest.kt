package dev.vilquer.petcarescheduler.core.domain.assistant

import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleRule
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class CareDraftTest {
    private val now = Instant.parse("2026-07-14T12:00:00Z")
    private val householdId = HouseholdId(UUID.randomUUID())
    private val actorId = TutorId(7)

    @Test
    fun `extraction only becomes ready when every required field is present`() {
        val processing = draft()
        val needsInput = processing.applyExtraction(
            extractedFields = CareDraftFields(zoneId = ZoneId.of("America/Sao_Paulo"), responsibleTutorId = actorId),
            extractedEvidence = emptyMap(),
            extractedMissing = setOf(CareDraftField.PET, CareDraftField.TYPE, CareDraftField.TITLE, CareDraftField.START_AT),
            extractedWarnings = emptyList(),
            extractedProvenance = emptyMap(),
            provider = "fake",
            model = "v1",
            at = now.plusSeconds(1),
        )

        assertEquals(CareDraftStatus.NEEDS_INPUT, needsInput.status)
        assertEquals(CareDraftFieldProvenance.MISSING, needsInput.provenance[CareDraftField.PET])

        val ready = needsInput.revise(validFields(), now.plusSeconds(2))
        assertEquals(CareDraftStatus.READY, ready.status)
        assertEquals(emptySet<CareDraftField>(), ready.missingFields)
    }

    @Test
    fun `blocking ambiguity prevents ready until human revision`() {
        val ambiguous = draft().applyExtraction(
            extractedFields = validFields().copy(scheduleRule = null),
            extractedEvidence = emptyMap(),
            extractedMissing = setOf(CareDraftField.SCHEDULE),
            extractedWarnings = listOf(CareDraftWarning("SCHEDULE_AMBIGUOUS", "Informe horários exatos.", true)),
            extractedProvenance = mapOf(CareDraftField.SCHEDULE to CareDraftFieldProvenance.NEEDS_REVIEW),
            provider = "fake",
            model = "v1",
            at = now.plusSeconds(1),
        )

        assertEquals(CareDraftStatus.NEEDS_INPUT, ambiguous.status)
        assertEquals(CareDraftStatus.READY, ambiguous.revise(validFields(), now.plusSeconds(2)).status)
    }

    @Test
    fun `confirmation is a one way transition and requires ready draft`() {
        val ready = draft().applyExtraction(
            extractedFields = validFields(),
            extractedEvidence = emptyMap(),
            extractedMissing = emptySet(),
            extractedWarnings = emptyList(),
            extractedProvenance = emptyMap(),
            provider = "fake",
            model = "v1",
            at = now.plusSeconds(1),
        )
        val planId = CarePlanId(UUID.randomUUID())
        val confirmed = ready.confirm(planId, now.plusSeconds(2))

        assertEquals(CareDraftStatus.CONFIRMED, confirmed.status)
        assertEquals(planId, confirmed.planId)
        assertThrows(IllegalArgumentException::class.java) { confirmed.cancel(now.plusSeconds(3)) }
    }

    private fun draft() = CareDraft(
        householdId = householdId,
        actorTutorId = actorId,
        channel = CareDraftChannel.WEB,
        inputType = CareDraftInputType.TEXT,
        inputHash = "a".repeat(64),
        fields = CareDraftFields(zoneId = ZoneId.of("America/Sao_Paulo"), responsibleTutorId = actorId),
        promptVersion = "care-draft-v1",
        createdAt = now,
        updatedAt = now,
        expiresAt = now.plusSeconds(3600),
    )

    private fun validFields() = CareDraftFields(
        petId = PetId(1),
        type = EventType.VACCINE,
        title = "Vacina",
        startAt = now.plusSeconds(3600),
        zoneId = ZoneId.of("America/Sao_Paulo"),
        scheduleRule = ScheduleRule.oneTime(),
        responsibleTutorId = actorId,
    )
}
