package dev.vilquer.petcarescheduler.usecase.contract.drivenports

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrence
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceAction
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.care.CarePlan
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanMaterializationCursor
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import java.time.Instant
import java.util.UUID

data class CareOccurrenceFilter(
    val from: Instant,
    val to: Instant,
    val petId: PetId? = null,
    val type: EventType? = null,
    val status: CareOccurrenceStatus? = null,
)

interface CarePlanRepositoryPort {
    fun save(plan: CarePlan): CarePlan
    fun findByIdAndTutor(id: CarePlanId, tutorId: TutorId): CarePlan?
    fun findByIdAndTutorForUpdate(id: CarePlanId, tutorId: TutorId): CarePlan?
    fun listByTutor(tutorId: TutorId, petId: PetId?, active: Boolean?, page: Int, size: Int): List<CarePlan>
    fun countByTutor(tutorId: TutorId, petId: PetId?, active: Boolean?): Long
    fun findActive(page: Int, size: Int): List<CarePlan>
    fun findByIdAndHousehold(id: CarePlanId, householdId: HouseholdId): CarePlan?
    fun findByIdAndHouseholdForUpdate(id: CarePlanId, householdId: HouseholdId): CarePlan?
    fun findBySourceDraftId(sourceDraftId: UUID, householdId: HouseholdId): CarePlan?
    fun listByHousehold(householdId: HouseholdId, petId: PetId?, active: Boolean?, page: Int, size: Int): List<CarePlan>
    fun countByHousehold(householdId: HouseholdId, petId: PetId?, active: Boolean?): Long
}

interface CarePlanMaterializationCursorRepositoryPort {
    fun save(cursor: CarePlanMaterializationCursor): CarePlanMaterializationCursor
    fun find(planId: CarePlanId, scheduleRevision: Int): CarePlanMaterializationCursor?
    fun findForUpdate(planId: CarePlanId, scheduleRevision: Int): CarePlanMaterializationCursor?
}

interface CareOccurrenceRepositoryPort {
    fun save(occurrence: CareOccurrence): CareOccurrence
    fun saveAllIfAbsent(occurrences: List<CareOccurrence>): Int
    fun findById(id: CareOccurrenceId): CareOccurrence?
    fun findPlanIdByIdAndHousehold(id: CareOccurrenceId, householdId: HouseholdId): CarePlanId?
    fun findByIdAndTutor(id: CareOccurrenceId, tutorId: TutorId): CareOccurrence?
    fun findByIdAndTutorForUpdate(id: CareOccurrenceId, tutorId: TutorId): CareOccurrence?
    fun search(tutorId: TutorId, filter: CareOccurrenceFilter, page: Int, size: Int): List<CareOccurrence>
    fun count(tutorId: TutorId, filter: CareOccurrenceFilter): Long
    fun cancelScheduledFrom(planId: CarePlanId, from: Instant, updatedAt: Instant): Int
    fun cancelAllScheduled(planId: CarePlanId, updatedAt: java.time.Instant): Int
    fun findReminderCandidates(from: Instant, to: Instant, limit: Int): List<CareOccurrence>
    fun findScheduledFrom(planId: CarePlanId, from: Instant): List<CareOccurrence>
    fun countByTutor(tutorId: TutorId): Long
    fun findUpcoming(tutorId: TutorId, from: Instant, to: Instant, limit: Int): List<CareOccurrence>
    fun findByIdAndHousehold(id: CareOccurrenceId, householdId: HouseholdId): CareOccurrence?
    fun findByIdAndHouseholdForUpdate(id: CareOccurrenceId, householdId: HouseholdId): CareOccurrence?
    fun searchByHousehold(householdId: HouseholdId, filter: CareOccurrenceFilter, page: Int, size: Int): List<CareOccurrence>
    fun countByHousehold(householdId: HouseholdId, filter: CareOccurrenceFilter): Long
    fun countByHousehold(householdId: HouseholdId): Long
    fun findUpcomingByHousehold(householdId: HouseholdId, from: Instant, to: Instant, limit: Int): List<CareOccurrence>
    fun findCriticalEscalationCandidates(before: Instant, limit: Int): List<CareOccurrence>
}

interface CareOccurrenceActionRepositoryPort {
    fun findByRequestId(requestId: UUID): CareOccurrenceAction?
    fun save(action: CareOccurrenceAction): CareOccurrenceAction
}
