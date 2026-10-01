package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareOccurrenceRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareReminderNotificationTarget
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareReminderOutboxPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.NotificationPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.DispatchPendingCareRemindersUseCase

class CareReminderRelayService(
    private val outbox: CareReminderOutboxPort,
    private val occurrences: CareOccurrenceRepositoryPort,
    private val notifier: NotificationPort,
    private val members: dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdMemberRepositoryPort,
    private val maxAttempts: Int = 5,
    private val batchSize: Int = 100,
) : DispatchPendingCareRemindersUseCase {
    override fun dispatchPendingCareReminders() {
        outbox.findPendingDelivery(maxAttempts, batchSize).forEach { message ->
            val occurrence = occurrences.findById(message.occurrenceId)
            if (occurrence == null || occurrence.status != CareOccurrenceStatus.SCHEDULED ||
                occurrence.responsibleTutorId != message.tutorId) {
                outbox.markCancelled(message.id!!, java.time.Instant.now())
                return@forEach
            }
            val member = members.findAccess(message.tutorId, occurrence.householdId)
            if (member == null || member.role == dev.vilquer.petcarescheduler.core.domain.household.HouseholdRole.VIEWER) {
                outbox.markCancelled(message.id!!, java.time.Instant.now())
                return@forEach
            }
            val delivered = notifier.sendCareReminder(
                CareReminderNotificationTarget(
                    occurrence.id, occurrence.type, occurrence.title, occurrence.dueAt,
                    message.tutorEmail, message.petName,
                    occurrence.zoneId.id,
                ),
            )
            if (delivered) outbox.markSent(message.id!!) else outbox.incrementAttempts(message.id!!)
        }
    }

}
