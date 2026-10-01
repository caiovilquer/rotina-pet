package dev.vilquer.petcarescheduler.usecase.contract.drivenports

import dev.vilquer.petcarescheduler.core.domain.assistant.AiInteraction
import dev.vilquer.petcarescheduler.core.domain.assistant.AssistantFeedback
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraft
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftAction
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId

interface CareDraftRepositoryPort {
    fun save(draft: CareDraft): CareDraft
    fun findByIdAndHousehold(id: CareDraftId, householdId: HouseholdId): CareDraft?
    fun findByIdAndHouseholdForUpdate(id: CareDraftId, householdId: HouseholdId): CareDraft?
    fun listByHousehold(householdId: HouseholdId, page: Int, size: Int): List<CareDraft>
    fun countByHousehold(householdId: HouseholdId): Long
    fun findActionByRequestId(requestId: java.util.UUID): CareDraftAction?
    fun saveAction(action: CareDraftAction): CareDraftAction
    fun saveFeedback(feedback: AssistantFeedback): AssistantFeedback
}

fun interface AiInteractionRepositoryPort {
    fun save(interaction: AiInteraction): AiInteraction
}
