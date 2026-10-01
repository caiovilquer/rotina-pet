package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.CareOccurrenceJpa
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface CareOccurrenceJpaRepository : JpaRepository<CareOccurrenceJpa, UUID> {
    fun existsByPlanIdAndScheduleRevisionAndSequence(planId: UUID, scheduleRevision: Int, sequence: Long): Boolean

    @Query("select o from CareOccurrenceJpa o where o.id = :id and o.tutorId = :tutorId")
    fun findOwned(@Param("id") id: UUID, @Param("tutorId") tutorId: Long): CareOccurrenceJpa?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CareOccurrenceJpa o where o.id = :id and o.tutorId = :tutorId")
    fun findOwnedForUpdate(@Param("id") id: UUID, @Param("tutorId") tutorId: Long): CareOccurrenceJpa?

    @Query("""
        select o from CareOccurrenceJpa o
        where o.tutorId = :tutorId
          and o.dueAt >= :from and o.dueAt < :to
          and (:petId is null or o.petId = :petId)
          and (:type is null or o.type = :type)
          and (:status is null or o.status = :status)
        order by o.dueAt asc, o.id asc
    """)
    fun search(
        @Param("tutorId") tutorId: Long,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        @Param("petId") petId: Long?,
        @Param("type") type: EventType?,
        @Param("status") status: CareOccurrenceStatus?,
        pageable: Pageable,
    ): Page<CareOccurrenceJpa>

    @Modifying
    @Query("""
        update CareOccurrenceJpa o set o.status = :cancelled, o.updatedAt = :updatedAt
        where o.planId = :planId and o.status = :scheduled and o.dueAt >= :from
    """)
    fun cancelScheduledFrom(
        @Param("planId") planId: UUID,
        @Param("from") from: Instant,
        @Param("scheduled") scheduled: CareOccurrenceStatus,
        @Param("cancelled") cancelled: CareOccurrenceStatus,
        @Param("updatedAt") updatedAt: Instant,
    ): Int

    @Modifying
    @Query("""
        update CareOccurrenceJpa o set o.status = :cancelled, o.updatedAt = :updatedAt
        where o.planId = :planId and o.status = :scheduled
    """)
    fun cancelAllScheduled(
        @Param("planId") planId: UUID,
        @Param("scheduled") scheduled: CareOccurrenceStatus,
        @Param("cancelled") cancelled: CareOccurrenceStatus,
        @Param("updatedAt") updatedAt: Instant,
    ): Int

    fun findAllByStatusAndDueAtGreaterThanEqualAndDueAtLessThanOrderByDueAtAsc(
        status: CareOccurrenceStatus,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): List<CareOccurrenceJpa>

    fun countByTutorId(tutorId: Long): Long

    @Query("""
        select o from CareOccurrenceJpa o
        where o.tutorId = :tutorId and o.status = :status
          and o.dueAt >= :from and o.dueAt < :to
        order by o.dueAt asc, o.id asc
    """)
    fun findUpcoming(
        @Param("tutorId") tutorId: Long,
        @Param("status") status: CareOccurrenceStatus,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        pageable: Pageable,
    ): List<CareOccurrenceJpa>

    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): CareOccurrenceJpa?

    @Query("select o.planId from CareOccurrenceJpa o where o.id = :id and o.householdId = :householdId")
    fun findPlanIdByHousehold(@Param("id") id: UUID, @Param("householdId") householdId: UUID): UUID?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CareOccurrenceJpa o where o.id = :id and o.householdId = :householdId")
    fun findByHouseholdForUpdate(@Param("id") id: UUID, @Param("householdId") householdId: UUID): CareOccurrenceJpa?

    @Query("""
        select o from CareOccurrenceJpa o where o.householdId = :householdId
          and o.dueAt >= :from and o.dueAt < :to
          and (:petId is null or o.petId = :petId)
          and (:type is null or o.type = :type)
          and (:status is null or o.status = :status)
        order by o.dueAt asc, o.id asc
    """)
    fun searchByHousehold(@Param("householdId") householdId: UUID, @Param("from") from: Instant,
        @Param("to") to: Instant, @Param("petId") petId: Long?, @Param("type") type: EventType?,
        @Param("status") status: CareOccurrenceStatus?, pageable: Pageable): Page<CareOccurrenceJpa>

    fun countByHouseholdId(householdId: UUID): Long

    @Query("""
        select o from CareOccurrenceJpa o where o.householdId = :householdId and o.status = :status
          and o.dueAt >= :from and o.dueAt < :to order by o.dueAt asc, o.id asc
    """)
    fun findUpcomingByHousehold(@Param("householdId") householdId: UUID, @Param("status") status: CareOccurrenceStatus,
        @Param("from") from: Instant, @Param("to") to: Instant, pageable: Pageable): List<CareOccurrenceJpa>

    fun findAllByStatusAndCriticalTrueAndDueAtLessThanEqualOrderByDueAtAsc(
        status: CareOccurrenceStatus, before: Instant, pageable: Pageable,
    ): List<CareOccurrenceJpa>

    fun findAllByPlanIdAndStatusAndDueAtGreaterThanEqualOrderByDueAtAsc(
        planId: UUID,
        status: CareOccurrenceStatus,
        from: Instant,
    ): List<CareOccurrenceJpa>
}
