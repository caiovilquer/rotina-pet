package dev.vilquer.petcarescheduler.usecase.contract.drivenports

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import java.time.Instant
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdTimezone

data class CareReminderOutboxMessage(
    val id: Long? = null,
    val occurrenceId: CareOccurrenceId,
    val tutorId: TutorId,
    val tutorEmail: String,
    val petName: String?,
    val createdAt: Instant,
    val attempts: Int = 0,
)

interface CareReminderOutboxPort {
    fun enqueueIfAbsent(message: CareReminderOutboxMessage)
    fun findPendingDelivery(maxAttempts: Int, limit: Int): List<CareReminderOutboxMessage>
    fun markSent(id: Long)
    fun markCancelled(id: Long, at: Instant)
    fun cancelPendingForPlan(planId: CarePlanId, from: Instant, at: Instant): Int
    fun incrementAttempts(id: Long)
}

data class CareReminderNotificationTarget(
    val occurrenceId: CareOccurrenceId,
    val type: EventType,
    val title: String,
    val dueAt: Instant,
    val tutorEmail: String,
    val petName: String?,
    val timezone: String = HouseholdTimezone.DEFAULT_ID,
)
