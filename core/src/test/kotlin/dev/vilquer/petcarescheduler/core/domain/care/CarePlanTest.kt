package dev.vilquer.petcarescheduler.core.domain.care

import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

class CarePlanTest {
    private val start = Instant.parse("2026-07-12T10:00:00Z")
    private val generatedAt = Instant.parse("2026-07-12T09:00:00Z")

    @Test
    fun `occurrence ids are deterministic per plan revision and global sequence`() {
        val plan = plan(ScheduleRule.fixed(Duration.ofHours(12), repetitions = 2))
        val firstPass = plan.materialize(plan.initialCursor(start, generatedAt), start.plus(5, ChronoUnit.DAYS), generatedAt)
        val secondPass = plan.materialize(plan.initialCursor(start, generatedAt), start.plus(5, ChronoUnit.DAYS), generatedAt.plusSeconds(60))

        assertEquals(firstPass.occurrences.map { it.id }, secondPass.occurrences.map { it.id })
        val revised = plan.copy(scheduleRevision = 1)
        assertNotEquals(
            firstPass.occurrences.first().id,
            revised.materialize(revised.initialCursor(start, generatedAt), start.plusSeconds(1), generatedAt).occurrences.first().id,
        )
    }

    @Test
    fun `legacy occurrence id bytes stay frozen after sequence becomes Long`() {
        val fixedPlan = plan(ScheduleRule.calendar(CalendarIntervalUnit.DAY)).copy(
            id = CarePlanId(UUID.fromString("00000000-0000-0000-0000-000000000123")),
        )
        val generated = fixedPlan.materialize(
            fixedPlan.initialCursor(start, generatedAt),
            start.plus(3_650, ChronoUnit.DAYS),
            generatedAt,
        ).occurrences

        assertEquals(CareOccurrenceId(UUID.fromString("af263c1f-c96d-33d0-ab5c-a7d2053e4842")), generated.first().id)
        assertEquals(499L, generated.last().sequence)
        assertEquals(CareOccurrenceId(UUID.fromString("b384c31c-76ba-36f1-94f4-590f8d631a9e")), generated.last().id)
    }

    @Test
    fun `cursor continues through multiple batches without a lifetime cap`() {
        val plan = plan(ScheduleRule.fixed(Duration.ofMinutes(30), repetitions = 1_205))
        var cursor = plan.initialCursor(start, generatedAt)
        val occurrences = mutableListOf<CareOccurrence>()

        repeat(3) {
            val batch = plan.materialize(cursor, start.plus(30, ChronoUnit.DAYS), generatedAt.plusSeconds(it.toLong()))
            occurrences += batch.occurrences
            cursor = batch.cursor
        }

        assertEquals(listOf(500, 500, 205), occurrences.chunked(500).map { it.size })
        assertEquals((0L..1_204L).toList(), occurrences.map { it.sequence })
        assertEquals(CarePlanMaterializationStatus.EXHAUSTED, cursor.status)
    }

    private fun plan(rule: ScheduleRule) = CarePlan(
        householdId = HouseholdId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        tutorId = TutorId(1),
        petId = PetId(1),
        responsibleTutorId = TutorId(1),
        type = EventType.MEDICINE,
        title = "Remédio",
        startAt = start,
        zoneId = ZoneId.of("UTC"),
        scheduleRule = rule,
        createdAt = generatedAt,
        updatedAt = generatedAt,
    )
}
