package dev.vilquer.petcarescheduler.usecase.contract.drivingports

import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.usecase.command.AddAssistantAnswerFeedbackCommand
import dev.vilquer.petcarescheduler.usecase.command.AskPetHistoryQuestionCommand
import dev.vilquer.petcarescheduler.usecase.command.ReindexKnowledgeSourceCommand
import dev.vilquer.petcarescheduler.usecase.result.KnowledgeSourceResult
import dev.vilquer.petcarescheduler.usecase.result.PetHistoryAnswerResult

interface PetHistoryAssistantUseCase {
    fun ask(command: AskPetHistoryQuestionCommand, access: HouseholdAccess): PetHistoryAnswerResult
    fun addFeedback(command: AddAssistantAnswerFeedbackCommand, access: HouseholdAccess)
}

interface KnowledgeIndexUseCase {
    fun processBatch()
    fun listSources(petId: PetId, access: HouseholdAccess): List<KnowledgeSourceResult>
    fun reindex(command: ReindexKnowledgeSourceCommand, access: HouseholdAccess): KnowledgeSourceResult
}
