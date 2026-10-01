package dev.vilquer.petcarescheduler.application.adapter.input.rest

import dev.vilquer.petcarescheduler.application.adapter.input.security.CurrentHousehold
import dev.vilquer.petcarescheduler.application.adapter.input.security.CurrentJwt
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftField
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftFields
import dev.vilquer.petcarescheduler.core.domain.assistant.CareDraftId
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.usecase.command.AddCareDraftFeedbackCommand
import dev.vilquer.petcarescheduler.usecase.command.CancelCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.ConfirmCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.CorrectCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.command.GenerateCareDraftCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CareDraftUseCase
import dev.vilquer.petcarescheduler.usecase.result.CareDraftConfirmationResult
import dev.vilquer.petcarescheduler.usecase.result.CareDraftPageResult
import dev.vilquer.petcarescheduler.usecase.result.CareDraftResult
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class GenerateCareDraftRequest(
    val requestId: UUID,
    @field:NotBlank @field:Size(max = 4_000) val instruction: String,
)

data class CorrectCareDraftRequest(
    val requestId: UUID,
    @field:Min(0) val expectedVersion: Long,
    @field:Valid val fields: CarePlanRequest,
)

data class CareDraftActionRequest(
    val requestId: UUID,
    @field:Min(0) val expectedVersion: Long,
)

data class CareDraftFeedbackRequest(
    val requestId: UUID,
    val positive: Boolean,
    @field:Size(max = 20) val correctedFields: Set<CareDraftField> = emptySet(),
    @field:Size(max = 80) val reason: String? = null,
    @field:Size(max = 1_000) val comment: String? = null,
)

@RestController
@RequestMapping("/api/v1/assistant/care-drafts")
@Validated
class AssistantCareDraftController(
    private val drafts: CareDraftUseCase,
    private val household: CurrentHousehold,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun generate(
        @Valid @RequestBody body: GenerateCareDraftRequest,
        @AuthenticationPrincipal jwt: CurrentJwt,
    ): CareDraftResult = drafts.generate(
        GenerateCareDraftCommand(body.instruction, body.requestId),
        household.resolve(jwt),
    )

    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: CurrentJwt,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): CareDraftPageResult = drafts.list(household.resolve(jwt), page, size)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID, @AuthenticationPrincipal jwt: CurrentJwt): CareDraftResult =
        drafts.get(CareDraftId(id), household.resolve(jwt))

    @PutMapping("/{id}")
    fun correct(
        @PathVariable id: UUID,
        @Valid @RequestBody body: CorrectCareDraftRequest,
        @AuthenticationPrincipal jwt: CurrentJwt,
    ): CareDraftResult {
        val access = household.resolve(jwt)
        val zoneId = CareWallClock.zone(body.fields.zoneId, access.zoneId)
        return drafts.correct(
            CorrectCareDraftCommand(
                draftId = CareDraftId(id),
                expectedVersion = body.expectedVersion,
                fields = body.fields.toDraftFields(zoneId),
                requestId = body.requestId,
            ),
            access,
        )
    }

    @PostMapping("/{id}/confirm")
    fun confirm(
        @PathVariable id: UUID,
        @Valid @RequestBody body: CareDraftActionRequest,
        @AuthenticationPrincipal jwt: CurrentJwt,
    ): CareDraftConfirmationResult = drafts.confirm(
        ConfirmCareDraftCommand(CareDraftId(id), body.expectedVersion, body.requestId),
        household.resolve(jwt),
    )

    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @Valid @RequestBody body: CareDraftActionRequest,
        @AuthenticationPrincipal jwt: CurrentJwt,
    ): CareDraftResult = drafts.cancel(
        CancelCareDraftCommand(CareDraftId(id), body.expectedVersion, body.requestId),
        household.resolve(jwt),
    )

    @PostMapping("/{id}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun feedback(
        @PathVariable id: UUID,
        @Valid @RequestBody body: CareDraftFeedbackRequest,
        @AuthenticationPrincipal jwt: CurrentJwt,
    ) = drafts.addFeedback(
        AddCareDraftFeedbackCommand(
            draftId = CareDraftId(id),
            positive = body.positive,
            correctedFields = body.correctedFields,
            reason = body.reason?.trim()?.uppercase(),
            comment = body.comment,
            requestId = body.requestId,
        ),
        household.resolve(jwt),
    )

    private fun CarePlanRequest.toDraftFields(zoneId: java.time.ZoneId) = CareDraftFields(
        petId = PetId(petId),
        type = type,
        title = title.trim(),
        instructions = instructions?.trim()?.takeIf(String::isNotEmpty),
        startAt = CareWallClock.parse(startAt, zoneId),
        zoneId = zoneId,
        scheduleRule = scheduleRule.toDomain(zoneId),
        reminderMinutesBefore = reminderMinutesBefore,
        responsibleTutorId = responsibleTutorId?.let(::TutorId),
        critical = critical,
        escalationDelayMinutes = escalationDelayMinutes,
        escalationTutorId = escalationTutorId?.let(::TutorId),
        estimatedCostAmount = estimatedCostAmount,
        estimatedCostCurrency = estimatedCostCurrency?.trim()?.uppercase(),
    )
}
