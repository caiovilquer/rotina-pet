package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.FakeCareReminderOutbox
import dev.vilquer.petcarescheduler.application.FakeNotifier
import dev.vilquer.petcarescheduler.application.InMemoryCareOccurrenceRepo
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrence
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.application.TEST_HOUSEHOLD_ID
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareReminderOutboxMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CareReminderRelayServiceTest {
    private val tutorId = TutorId(1)
    private val now = Instant.parse("2026-07-12T12:00:00Z")

    @Test
    fun `successful delivery marks outbox sent and includes occurrence snapshot`() {
        val occurrence = occurrence(CareOccurrenceStatus.SCHEDULED)
        val outbox = FakeCareReminderOutbox()
        outbox.enqueueIfAbsent(message(occurrence.id))
        val notifier = FakeNotifier()

        CareReminderRelayService(outbox, InMemoryCareOccurrenceRepo(listOf(occurrence)), notifier, dev.vilquer.petcarescheduler.application.FakeHouseholdMemberRepo(tutorId))
            .dispatchPendingCareReminders()

        assertEquals("Antibiótico", notifier.notifiedCare.single().title)
        assertTrue(outbox.isSent(outbox.all().single().id))
    }

    @Test
    fun `delivery failure is retried and completed occurrence is never notified`() {
        val scheduled = occurrence(CareOccurrenceStatus.SCHEDULED)
        val failedOutbox = FakeCareReminderOutbox().also { it.enqueueIfAbsent(message(scheduled.id)) }
        val failingNotifier = FakeNotifier().also { it.deliverySucceeds = false }
        CareReminderRelayService(failedOutbox, InMemoryCareOccurrenceRepo(listOf(scheduled)), failingNotifier, dev.vilquer.petcarescheduler.application.FakeHouseholdMemberRepo(tutorId))
            .dispatchPendingCareReminders()
        assertEquals(1, failedOutbox.all().single().attempts)
        assertFalse(failedOutbox.isSent(failedOutbox.all().single().id))

        val completed = occurrence(CareOccurrenceStatus.COMPLETED)
        val staleOutbox = FakeCareReminderOutbox().also { it.enqueueIfAbsent(message(completed.id)) }
        val notifier = FakeNotifier()
        CareReminderRelayService(staleOutbox, InMemoryCareOccurrenceRepo(listOf(completed)), notifier, dev.vilquer.petcarescheduler.application.FakeHouseholdMemberRepo(tutorId))
            .dispatchPendingCareReminders()
        assertTrue(notifier.notifiedCare.isEmpty())
        assertTrue(staleOutbox.isCancelled(staleOutbox.all().single().id))
    }

    private fun message(id: CareOccurrenceId) = CareReminderOutboxMessage(
        occurrenceId = id, tutorId = tutorId, tutorEmail = "ana@example.com", petName = "Luna", createdAt = now,
    )

    private fun occurrence(status: CareOccurrenceStatus): CareOccurrence = CareOccurrence(
        id = CareOccurrenceId(UUID.randomUUID()), planId = CarePlanId(UUID.randomUUID()), scheduleRevision = 0,
        householdId = TEST_HOUSEHOLD_ID, tutorId = tutorId, petId = PetId(1), responsibleTutorId = tutorId, sequence = 0L, type = EventType.MEDICINE,
        title = "Antibiótico", dueAt = Instant.parse("2026-07-12T10:00:00Z"), status = status,
        completedAt = if (status == CareOccurrenceStatus.COMPLETED) now else null,
        completedByTutorId = if (status == CareOccurrenceStatus.COMPLETED) tutorId else null,
        createdAt = now, updatedAt = now,
    )
}
