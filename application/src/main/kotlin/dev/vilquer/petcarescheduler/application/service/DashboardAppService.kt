package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.NotFoundException
import dev.vilquer.petcarescheduler.application.mapper.toSummary
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ClockPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EventRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareOccurrenceRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PetRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TutorRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.GetDashboardOverviewUseCase
import dev.vilquer.petcarescheduler.usecase.result.DashboardOverviewResult

class DashboardAppService(
    private val tutors: TutorRepositoryPort,
    private val pets: PetRepositoryPort,
    private val occurrences: CareOccurrenceRepositoryPort,
    private val clock: ClockPort,
) : GetDashboardOverviewUseCase {

    override fun getOverview(access: HouseholdAccess): DashboardOverviewResult {
        val tutor = tutors.findById(access.actorTutorId)
            ?: throw NotFoundException("Tutor ${access.actorTutorId.value} not found")
        val now = clock.now(access.zoneId).toInstant()
        val petItems = pets.listByHousehold(access.householdId, page = 0, size = MAX_PETS)

        return DashboardOverviewResult(
            firstName = tutor.firstName,
            lastName = tutor.lastName,
            email = tutor.email.value,
            avatar = tutor.avatar,
            avatarAssetId = tutor.avatarAssetId,
            totalPets = pets.countByHousehold(access.householdId),
            totalEvents = occurrences.countByHousehold(access.householdId),
            pets = petItems.map { it.toSummary() },
            upcomingEvents = occurrences.findUpcomingByHousehold(access.householdId, now, now.plus(java.time.Duration.ofDays(7)), MAX_UPCOMING).map {
                dev.vilquer.petcarescheduler.usecase.result.CareOccurrenceResult(
                    id = it.id.value,
                    version = it.version,
                    planId = it.planId.value,
                    petId = it.petId.value,
                    responsibleTutorId = it.responsibleTutorId.value,
                    type = it.type,
                    title = it.title,
                    instructions = it.instructions,
                    dueAt = it.dueAt,
                    dueAtLocal = it.dueAt.atZone(it.zoneId).toLocalDateTime(),
                    status = it.status,
                    completedAt = it.completedAt,
                    completedByTutorId = it.completedByTutorId?.value,
                    completionNote = it.completionNote,
                    critical = it.critical,
                    escalationDelayMinutes = it.escalationDelayMinutes,
                    escalationTutorId = it.escalationTutorId?.value,
                    estimatedCostAmount = it.estimatedCostAmount,
                    estimatedCostCurrency = it.estimatedCostCurrency,
                    canUndoUntil = it.completedAt?.plus(CareAppService.UNDO_WINDOW),
                    timezone = it.zoneId.id,
                )
            },
        )
    }

    companion object {
        private const val MAX_PETS = 100
        private const val MAX_UPCOMING = 20
    }
}
