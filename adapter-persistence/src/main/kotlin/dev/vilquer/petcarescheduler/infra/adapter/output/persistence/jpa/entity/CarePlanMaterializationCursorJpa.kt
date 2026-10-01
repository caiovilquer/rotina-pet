package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity

import dev.vilquer.petcarescheduler.core.domain.care.CarePlanMaterializationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.io.Serializable
import java.time.Instant
import java.util.UUID

data class CarePlanMaterializationCursorJpaId(
    var planId: UUID? = null,
    var scheduleRevision: Int = 0,
) : Serializable

@Entity
@IdClass(CarePlanMaterializationCursorJpaId::class)
@Table(name = "care_plan_materialization_cursor")
class CarePlanMaterializationCursorJpa {
    @Id @Column(name = "plan_id", nullable = false) lateinit var planId: UUID
    @Id @Column(name = "schedule_revision", nullable = false) var scheduleRevision: Int = 0
    @Column(name = "next_sequence") var nextSequence: Long? = null
    @Column(name = "next_due_at_instant") var nextDueAt: Instant? = null
    @Column(name = "materialized_through_instant") var materializedThrough: Instant? = null
    @Enumerated(EnumType.STRING) @Column(nullable = false) lateinit var status: CarePlanMaterializationStatus
    @Version var version: Long? = null
    @Column(name = "updated_at", nullable = false) lateinit var updatedAt: Instant
}
