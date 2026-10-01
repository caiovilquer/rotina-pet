package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity

import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.care.CalendarIntervalUnit
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID
import java.math.BigDecimal

@Entity
@Table(name = "care_plan")
class CarePlanJpa {
    @Id lateinit var id: UUID
    @Version var version: Long? = null
    @Column(name = "source_draft_id", unique = true) var sourceDraftId: UUID? = null
    @Column(name = "schedule_revision", nullable = false) var scheduleRevision: Int = 0
    @Column(name = "household_id", nullable = false) lateinit var householdId: UUID
    @Column(name = "tutor_id", nullable = false) var tutorId: Long = 0
    @Column(name = "pet_id", nullable = false) var petId: Long = 0
    @Column(name = "responsible_tutor_id", nullable = false) var responsibleTutorId: Long = 0
    @Enumerated(EnumType.STRING) @Column(nullable = false) lateinit var type: EventType
    @Column(nullable = false, length = 120) lateinit var title: String
    @Column(length = 2000) var instructions: String? = null
    @Column(name = "start_at_instant", nullable = false) lateinit var startAt: Instant
    @Column(name = "zone_id", nullable = false, length = 64) lateinit var zoneId: String
    @Enumerated(EnumType.STRING) @Column(name = "schedule_kind", nullable = false) lateinit var scheduleKind: ScheduleKind
    @Enumerated(EnumType.STRING) @Column(name = "calendar_unit") var calendarUnit: CalendarIntervalUnit? = null
    @Column(name = "interval_count") var intervalCount: Long? = null
    @Column(name = "fixed_interval_seconds") var fixedIntervalSeconds: Long? = null
    @Column(name = "daily_times", length = 512) var dailyTimes: String? = null
    @Column(name = "repetitions") var repetitions: Long? = null
    @Column(name = "end_at_instant") var endAt: Instant? = null
    @Column(name = "reminder_minutes_before", nullable = false) var reminderMinutesBefore: Int = 0
    @Column(nullable = false) var critical: Boolean = false
    @Column(name = "escalation_delay_minutes") var escalationDelayMinutes: Int? = null
    @Column(name = "escalation_tutor_id") var escalationTutorId: Long? = null
    @Column(name = "estimated_cost_amount", precision = 12, scale = 2) var estimatedCostAmount: BigDecimal? = null
    @Column(name = "estimated_cost_currency", length = 3) var estimatedCostCurrency: String? = null
    @Column(nullable = false) var active: Boolean = true
    @Column(name = "created_at", nullable = false) lateinit var createdAt: Instant
    @Column(name = "updated_at", nullable = false) lateinit var updatedAt: Instant

    override fun equals(other: Any?) = this === other || (other is CarePlanJpa && ::id.isInitialized && other::id.isInitialized && id == other.id)
    override fun hashCode() = if (::id.isInitialized) id.hashCode() else 0
}
