package dev.vilquer.petcarescheduler.usecase.contract.drivingports

import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.usecase.command.AddCareDraftFeedbackCommand
import dev.vilquer.petcarescheduler.usecase.command.CancelCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.ConfirmCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.CorrectCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.GenerateCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.result.CareDraftConfirmationResult
import dev.vilquer.petcarescheduler.usecase.result.CareDraftPageResult
import dev.vilquer.petcarescheduler.usecase.result.CareDraftResult

interface CareDraftUseCase {
    fun generate(command: GenerateCareDraftCommand, access: HouseholdAccess): CareDraftResult
    fun get(id: CareDraftId, access: HouseholdAccess): CareDraftResult
    fun list(access: HouseholdAccess, page: Int, size: Int): CareDraftPageResult
    fun correct(command: CorrectCareDraftCommand, access: HouseholdAccess): CareDraftResult
    fun confirm(command: ConfirmCareDraftCommand, access: HouseholdAccess): CareDraftConfirmationResult
    fun cancel(command: CancelCareDraftCommand, access: HouseholdAccess): CareDraftResult
    fun addFeedback(command: AddCareDraftFeedbackCommand, access: HouseholdAccess)
}
