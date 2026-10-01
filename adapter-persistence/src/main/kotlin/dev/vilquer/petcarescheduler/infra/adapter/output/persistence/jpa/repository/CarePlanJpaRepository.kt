package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository

import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CarePlanJpa
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface CarePlanJpaRepository : JpaRepository<CarePlanJpa, UUID> {
    @Query("select p from CarePlanJpa p where p.id = :id and p.tutorId = :tutorId")
    fun findOwned(@Param("id") id: UUID, @Param("tutorId") tutorId: Long): CarePlanJpa?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CarePlanJpa p where p.id = :id and p.tutorId = :tutorId")
    fun findOwnedForUpdate(@Param("id") id: UUID, @Param("tutorId") tutorId: Long): CarePlanJpa?

    @Query("""
        select p from CarePlanJpa p
        where p.tutorId = :tutorId
          and (:petId is null or p.petId = :petId)
          and (:active is null or p.active = :active)
        order by p.active desc, p.startAt asc, p.id asc
    """)
    fun findOwnedPage(
        @Param("tutorId") tutorId: Long,
        @Param("petId") petId: Long?,
        @Param("active") active: Boolean?,
        pageable: Pageable,
    ): Page<CarePlanJpa>

    fun findAllByActiveTrueOrderByUpdatedAtAsc(pageable: Pageable): List<CarePlanJpa>

    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): CarePlanJpa?
    fun findBySourceDraftIdAndHouseholdId(sourceDraftId: UUID, householdId: UUID): CarePlanJpa?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CarePlanJpa p where p.id = :id and p.householdId = :householdId")
    fun findByHouseholdForUpdate(@Param("id") id: UUID, @Param("householdId") householdId: UUID): CarePlanJpa?

    @Query("""
        select p from CarePlanJpa p where p.householdId = :householdId
          and (:petId is null or p.petId = :petId) and (:active is null or p.active = :active)
        order by p.active desc, p.startAt asc, p.id asc
    """)
    fun findHouseholdPage(@Param("householdId") householdId: UUID, @Param("petId") petId: Long?,
                          @Param("active") active: Boolean?, pageable: Pageable): Page<CarePlanJpa>
}
