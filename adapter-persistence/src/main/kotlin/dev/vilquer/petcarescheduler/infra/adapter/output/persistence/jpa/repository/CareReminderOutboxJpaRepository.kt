package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository

import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CareReminderOutboxJpa
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface CareReminderOutboxJpaRepository : JpaRepository<CareReminderOutboxJpa, Long> {
    fun existsByOccurrenceIdAndCancelledAtIsNull(occurrenceId: UUID): Boolean
    fun findAllBySentAtIsNullAndCancelledAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(maxAttempts: Int, pageable: Pageable): List<CareReminderOutboxJpa>

    @Modifying
    @Query(value = """
        update care_reminder_outbox r set cancelled_at = :at
        where r.sent_at is null and r.cancelled_at is null
          and exists (select 1 from care_occurrence o
              where o.id = r.occurrence_id and o.plan_id = :planId and o.due_at_instant >= :fromAt)
    """, nativeQuery = true)
    fun cancelPendingForPlan(@Param("planId") planId: UUID, @Param("fromAt") from: Instant, @Param("at") at: Instant): Int
}
