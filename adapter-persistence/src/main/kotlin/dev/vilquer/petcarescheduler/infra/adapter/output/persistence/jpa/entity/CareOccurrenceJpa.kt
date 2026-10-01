package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
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
@Table(name = "care_occurrence")
class CareOccurrenceJpa {
    @Id lateinit var id: UUID
    @Version var version: Long? = null
    @Column(name = "plan_id", nullable = false) lateinit var planId: UUID
    @Column(name = "schedule_revision", nullable = false) var scheduleRevision: Int = 0
    @Column(name = "household_id", nullable = false) lateinit var householdId: UUID
    @Column(name = "tutor_id", nullable = false) var tutorId: Long = 0
    @Column(name = "pet_id", nullable = false) var petId: Long = 0
    @Column(name = "responsible_tutor_id", nullable = false) var responsibleTutorId: Long = 0
    @Column(name = "sequence_number", nullable = false) var sequence: Long = 0
    @Enumerated(EnumType.STRING) @Column(nullable = false) lateinit var type: EventType
    @Column(nullable = false, length = 120) lateinit var title: String
    @Column(length = 2000) var instructions: String? = null
    @Column(name = "due_at_instant", nullable = false) lateinit var dueAt: Instant
    @Column(name = "zone_id", nullable = false, length = 64) lateinit var zoneId: String
    @Enumerated(EnumType.STRING) @Column(nullable = false) lateinit var status: CareOccurrenceStatus
    @Column(name = "completed_at") var completedAt: Instant? = null
    @Column(name = "completed_by_tutor_id") var completedByTutorId: Long? = null
    @Column(name = "completion_note", length = 500) var completionNote: String? = null
    @Column(nullable = false) var critical: Boolean = false
    @Column(name = "escalation_delay_minutes") var escalationDelayMinutes: Int? = null
    @Column(name = "escalation_tutor_id") var escalationTutorId: Long? = null
    @Column(name = "estimated_cost_amount", precision = 12, scale = 2) var estimatedCostAmount: BigDecimal? = null
    @Column(name = "estimated_cost_currency", length = 3) var estimatedCostCurrency: String? = null
    @Column(name = "created_at", nullable = false) lateinit var createdAt: Instant
    @Column(name = "updated_at", nullable = false) lateinit var updatedAt: Instant
    @Column(name = "legacy_event_id", unique = true) var legacyEventId: Long? = null

    override fun equals(other: Any?) = this === other || (other is CareOccurrenceJpa && ::id.isInitialized && other::id.isInitialized && id == other.id)
    override fun hashCode() = if (::id.isInitialized) id.hashCode() else 0
}
