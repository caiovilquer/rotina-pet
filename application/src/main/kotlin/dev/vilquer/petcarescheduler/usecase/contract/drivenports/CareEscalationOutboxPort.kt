package dev.vilquer.petcarescheduler.usecase.contract.drivenports

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import java.time.Instant
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId

data class CareEscalationOutboxMessage(
    val id: Long? = null,
    val occurrenceId: CareOccurrenceId,
    val householdId: HouseholdId,
    val recipientTutorId: TutorId,
    val recipientEmail: String,
    val petName: String,
    val careTitle: String,
    val dueAt: Instant,
    val createdAt: Instant,
    val attempts: Int = 0,
)

interface CareEscalationOutboxPort {
    fun enqueueIfAbsent(message: CareEscalationOutboxMessage)
    fun findPending(maxAttempts: Int, limit: Int): List<CareEscalationOutboxMessage>
    fun markSent(id: Long)
    fun markCancelled(id: Long, at: Instant)
    fun cancelPendingForPlan(planId: CarePlanId, from: Instant, at: Instant): Int
    fun incrementAttempts(id: Long)
}
