package dev.vilquer.petcarescheduler.infra.adapter.output.external

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CareEscalationOutboxJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.CareEscalationOutboxJpaRepository
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class CareEscalationOutboxJpaAdapter(private val jpa: CareEscalationOutboxJpaRepository) : CareEscalationOutboxPort {
    override fun enqueueIfAbsent(message: CareEscalationOutboxMessage) {
        if (jpa.existsByOccurrenceIdAndCancelledAtIsNull(message.occurrenceId.value)) return
        try { jpa.save(CareEscalationOutboxJpa().also {
            it.occurrenceId = message.occurrenceId.value; it.householdId = message.householdId.value
            it.recipientTutorId = message.recipientTutorId.value; it.recipientEmail = message.recipientEmail
            it.petName = message.petName; it.careTitle = message.careTitle; it.dueAt = message.dueAt; it.createdAt = message.createdAt
        }) } catch (_: DataIntegrityViolationException) { }
    }

    override fun findPending(maxAttempts: Int, limit: Int) =
        jpa.findAllBySentAtIsNullAndCancelledAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(maxAttempts, PageRequest.of(0, limit)).map {
            CareEscalationOutboxMessage(it.id, CareOccurrenceId(it.occurrenceId), HouseholdId(it.householdId),
                TutorId(it.recipientTutorId), it.recipientEmail, it.petName, it.careTitle, it.dueAt, it.createdAt, it.attempts)
        }
    override fun markSent(id: Long) { jpa.findById(id).orElse(null)?.let { it.sentAt = Instant.now(); jpa.save(it) } }
    override fun markCancelled(id: Long, at: Instant) { jpa.findById(id).orElse(null)?.let { it.cancelledAt = at; jpa.save(it) } }
    override fun cancelPendingForPlan(planId: CarePlanId, from: Instant, at: Instant) =
        jpa.cancelPendingForPlan(planId.value, from, at)
    override fun incrementAttempts(id: Long) { jpa.findById(id).orElse(null)?.let { it.attempts += 1; jpa.save(it) } }
}
