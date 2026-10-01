package dev.vilquer.petcarescheduler.infra.adapter.output.external

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CareReminderOutboxJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.CareReminderOutboxJpaRepository
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareReminderOutboxMessage
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareReminderOutboxPort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class CareReminderOutboxJpaAdapter(private val jpa: CareReminderOutboxJpaRepository) : CareReminderOutboxPort {
    override fun enqueueIfAbsent(message: CareReminderOutboxMessage) {
        if (jpa.existsByOccurrenceIdAndCancelledAtIsNull(message.occurrenceId.value)) return
        try {
            jpa.save(
                CareReminderOutboxJpa().also {
                    it.occurrenceId = message.occurrenceId.value
                    it.tutorId = message.tutorId.value
                    it.tutorEmail = message.tutorEmail
                    it.petName = message.petName
                    it.createdAt = message.createdAt
                },
            )
        } catch (_: DataIntegrityViolationException) {
            // A constraint UNIQUE é a última barreira contra duas instâncias.
        }
    }

    override fun findPendingDelivery(maxAttempts: Int, limit: Int) =
        jpa.findAllBySentAtIsNullAndCancelledAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(maxAttempts, PageRequest.of(0, limit)).map {
            CareReminderOutboxMessage(
                it.id, CareOccurrenceId(it.occurrenceId), TutorId(it.tutorId), it.tutorEmail,
                it.petName, it.createdAt, it.attempts,
            )
        }

    override fun markSent(id: Long) {
        val item = jpa.findById(id).orElse(null) ?: return
        item.sentAt = Instant.now()
        jpa.save(item)
    }

    override fun markCancelled(id: Long, at: Instant) {
        val item = jpa.findById(id).orElse(null) ?: return
        item.cancelledAt = at
        jpa.save(item)
    }

    override fun cancelPendingForPlan(planId: CarePlanId, from: Instant, at: Instant) =
        jpa.cancelPendingForPlan(planId.value, from, at)

    override fun incrementAttempts(id: Long) {
        val item = jpa.findById(id).orElse(null) ?: return
        item.attempts += 1
        jpa.save(item)
    }
}
