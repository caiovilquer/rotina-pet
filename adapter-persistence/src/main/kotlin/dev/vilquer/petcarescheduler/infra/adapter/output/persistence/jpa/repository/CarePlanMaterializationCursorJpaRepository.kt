package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository

import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CarePlanMaterializationCursorJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CarePlanMaterializationCursorJpaId
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface CarePlanMaterializationCursorJpaRepository :
    JpaRepository<CarePlanMaterializationCursorJpa, CarePlanMaterializationCursorJpaId> {
    fun findByPlanIdAndScheduleRevision(planId: UUID, scheduleRevision: Int): CarePlanMaterializationCursorJpa?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CarePlanMaterializationCursorJpa c where c.planId = :planId and c.scheduleRevision = :revision")
    fun findForUpdate(@Param("planId") planId: UUID, @Param("revision") revision: Int): CarePlanMaterializationCursorJpa?
}
