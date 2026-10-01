package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository

import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.AiInteractionJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.AssistantFeedbackJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CareDraftActionJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CareDraftJpa
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface CareDraftJpaRepository : JpaRepository<CareDraftJpa, UUID> {
    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): CareDraftJpa?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from CareDraftJpa d where d.id = :id and d.householdId = :householdId")
    fun findByHouseholdForUpdate(@Param("id") id: UUID, @Param("householdId") householdId: UUID): CareDraftJpa?

    fun findAllByHouseholdIdOrderByUpdatedAtDesc(householdId: UUID, pageable: Pageable): Page<CareDraftJpa>
    fun countByHouseholdId(householdId: UUID): Long
}

interface CareDraftActionJpaRepository : JpaRepository<CareDraftActionJpa, UUID> {
    fun findByRequestId(requestId: UUID): CareDraftActionJpa?
}

interface AiInteractionJpaRepository : JpaRepository<AiInteractionJpa, UUID>
interface AssistantFeedbackJpaRepository : JpaRepository<AssistantFeedbackJpa, UUID>
