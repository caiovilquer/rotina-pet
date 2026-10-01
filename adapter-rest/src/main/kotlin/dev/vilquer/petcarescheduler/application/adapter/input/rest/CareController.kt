package dev.vilquer.petcarescheduler.application.adapter.input.rest

import dev.vilquer.petcarescheduler.application.adapter.input.security.CurrentJwt
import dev.vilquer.petcarescheduler.application.adapter.input.security.tutorId
import dev.vilquer.petcarescheduler.application.adapter.input.security.CurrentHousehold
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.care.CalendarIntervalUnit
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleKind
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleRule
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.usecase.command.CompleteCareOccurrenceCommand
import dev.vilquer.petcarescheduler.usecase.command.CreateCarePlanCommand
import dev.vilquer.petcarescheduler.usecase.command.SearchCareOccurrencesQuery
import dev.vilquer.petcarescheduler.usecase.command.UndoCareOccurrenceCommand
import dev.vilquer.petcarescheduler.usecase.command.UpdateCarePlanCommand
import dev.vilquer.petcarescheduler.usecase.command.AssignCareOccurrenceCommand
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ClockPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CareOccurrenceUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CarePlanUseCase
import dev.vilquer.petcarescheduler.usecase.result.CareOccurrenceResult
import dev.vilquer.petcarescheduler.usecase.result.CareOccurrencesPageResult
import dev.vilquer.petcarescheduler.usecase.result.CarePlanResult
import dev.vilquer.petcarescheduler.usecase.result.CarePlansPageResult
import dev.vilquer.petcarescheduler.usecase.result.TodayCareResult
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.math.BigDecimal

data class CareScheduleRuleRequest(
    val kind: ScheduleKind,
    val calendarUnit: CalendarIntervalUnit? = null,
    @field:Positive @field:Max(365) val intervalCount: Long? = null,
    @field:Positive @field:Max(525_600) val fixedIntervalMinutes: Long? = null,
    @field:Size(max = 24) val dailyTimes: List<String> = emptyList(),
    @field:Positive val repetitions: Long? = null,
    val endAt: String? = null,
)

data class CarePlanRequest(
    @field:Positive val petId: Long,
    val type: EventType,
    @field:NotBlank @field:Size(max = 120) val title: String,
    @field:Size(max = 2_000) val instructions: String? = null,
    val startAt: String,
    val zoneId: String,
    @field:Valid val scheduleRule: CareScheduleRuleRequest = CareScheduleRuleRequest(ScheduleKind.ONE_TIME),
    @field:Min(0) @field:Max(10_080) val reminderMinutesBefore: Int = 0,
    val responsibleTutorId: Long? = null,
    val critical: Boolean = false,
    @field:Min(15) @field:Max(10_080) val escalationDelayMinutes: Int? = null,
    val escalationTutorId: Long? = null,
    @field:DecimalMin("0.01") @field:DecimalMax("9999999999.99") @field:Digits(integer = 10, fraction = 2)
    val estimatedCostAmount: BigDecimal? = null,
    @field:Pattern(regexp = "^[A-Za-z]{3}$") val estimatedCostCurrency: String? = null,
)

@RestController
@RequestMapping("/api/v1/care-plans")
@Validated
class CarePlanController(private val care: CarePlanUseCase, private val household: CurrentHousehold) {
    @PostMapping
    fun create(@Valid @RequestBody body: CarePlanRequest, @AuthenticationPrincipal jwt: CurrentJwt): ResponseEntity<CarePlanResult> {
        val access = household.resolve(jwt)
        val zoneId = CareWallClock.zone(body.zoneId, access.zoneId)
        val startAt = CareWallClock.parse(body.startAt, zoneId)
        val scheduleRule = body.scheduleRule.toDomain(zoneId)
        val result = care.create(
            CreateCarePlanCommand(
                PetId(body.petId), body.type, body.title, body.instructions, startAt, zoneId,
                scheduleRule, body.reminderMinutesBefore,
                body.responsibleTutorId?.let(::TutorId), body.critical, body.escalationDelayMinutes, body.escalationTutorId?.let(::TutorId),
                body.estimatedCostAmount, body.estimatedCostCurrency,
            ),
            access,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody body: CarePlanRequest,
        @AuthenticationPrincipal jwt: CurrentJwt,
    ): CarePlanResult {
        val access = household.resolve(jwt)
        val zoneId = CareWallClock.zone(body.zoneId, access.zoneId)
        val startAt = CareWallClock.parse(body.startAt, zoneId)
        val scheduleRule = body.scheduleRule.toDomain(zoneId)
        return care.update(
            UpdateCarePlanCommand(
                CarePlanId(id), body.type, body.title, body.instructions, startAt, zoneId,
                scheduleRule, body.reminderMinutesBefore,
                body.responsibleTutorId?.let(::TutorId), body.critical, body.escalationDelayMinutes, body.escalationTutorId?.let(::TutorId),
                body.estimatedCostAmount, body.estimatedCostCurrency,
            ),
            access,
        )
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID, @AuthenticationPrincipal jwt: CurrentJwt) =
        care.get(CarePlanId(id), household.resolve(jwt))

    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: CurrentJwt,
        @RequestParam(required = false) petId: Long?,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): CarePlansPageResult = care.list(household.resolve(jwt), petId?.let(::PetId), active, page, size)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivate(@PathVariable id: UUID, @AuthenticationPrincipal jwt: CurrentJwt) =
        care.deactivate(CarePlanId(id), household.resolve(jwt))

}

internal fun CareScheduleRuleRequest.toDomain(zoneId: ZoneId): ScheduleRule {
    val parsedEnd = endAt?.let { CareWallClock.parse(it, zoneId) }
    val rawTimes = try {
        dailyTimes.map(LocalTime::parse)
    } catch (_: Exception) {
        throw IllegalArgumentException("care_schedule_daily_times_invalid")
    }
    require(rawTimes.size == rawTimes.distinct().size) { "care_schedule_daily_times_invalid" }
    val parsedTimes = rawTimes.sorted()
    return when (kind) {
        ScheduleKind.ONE_TIME -> ScheduleRule.oneTime()
        ScheduleKind.CALENDAR_INTERVAL -> ScheduleRule.calendar(
            requireNotNull(calendarUnit) { "care_schedule_calendar_unit_required" },
            requireNotNull(intervalCount) { "care_schedule_interval_required" },
            repetitions,
            parsedEnd,
        )
        ScheduleKind.FIXED_INTERVAL -> ScheduleRule.fixed(
            Duration.ofMinutes(requireNotNull(fixedIntervalMinutes) { "care_schedule_fixed_interval_required" }),
            repetitions,
            parsedEnd,
        )
        ScheduleKind.DAILY_TIMES -> ScheduleRule.daily(parsedTimes, repetitions, parsedEnd)
    }
}

data class CareActionRequest(
    val requestId: UUID,
    @field:Size(max = 500) val note: String? = null,
)

data class CareAssignmentRequest(val expectedVersion: Long, @field:Positive val responsibleTutorId: Long)

@RestController
@RequestMapping("/api/v1/care-occurrences")
@Validated
class CareOccurrenceController(
    private val care: CareOccurrenceUseCase,
    private val clock: ClockPort,
    private val household: CurrentHousehold,
) {
    @GetMapping
    fun search(
        @AuthenticationPrincipal jwt: CurrentJwt,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) petId: Long?,
        @RequestParam(required = false) type: EventType?,
        @RequestParam(required = false) status: CareOccurrenceStatus?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): CareOccurrencesPageResult {
        val access = household.resolve(jwt)
        val now = clock.now(access.zoneId).toInstant()
        return care.search(
            SearchCareOccurrencesQuery(
                from?.let { CareWallClock.parse(it, access.zoneId) } ?: now.minus(Duration.ofDays(30)),
                to?.let { CareWallClock.parse(it, access.zoneId) } ?: now.plus(Duration.ofDays(90)),
                petId?.let(::PetId), type, status, page, size,
            ),
            access,
        )
    }

    @GetMapping("/today")
    fun today(@AuthenticationPrincipal jwt: CurrentJwt): TodayCareResult = care.today(household.resolve(jwt))

    @PostMapping("/{id}/complete")
    fun complete(
        @PathVariable id: UUID,
        @Valid @RequestBody body: CareActionRequest,
        @AuthenticationPrincipal jwt: CurrentJwt,
    ): CareOccurrenceResult = care.complete(
        CompleteCareOccurrenceCommand(CareOccurrenceId(id), body.requestId, body.note),
        household.resolve(jwt),
    )

    @PostMapping("/{id}/undo")
    fun undo(
        @PathVariable id: UUID,
        @Valid @RequestBody body: CareActionRequest,
        @AuthenticationPrincipal jwt: CurrentJwt,
    ): CareOccurrenceResult = care.undo(
        UndoCareOccurrenceCommand(CareOccurrenceId(id), body.requestId),
        household.resolve(jwt),
    )

    @PutMapping("/{id}/responsible")
    fun assign(
        @PathVariable id: UUID,
        @Valid @RequestBody body: CareAssignmentRequest,
        @AuthenticationPrincipal jwt: CurrentJwt,
    ): CareOccurrenceResult = care.assign(
        AssignCareOccurrenceCommand(CareOccurrenceId(id), body.expectedVersion, TutorId(body.responsibleTutorId)),
        household.resolve(jwt),
    )
}

/** Inputs without an offset use the immutable zone stored with the care plan. */
internal object CareWallClock {
    fun parse(value: String, zoneId: ZoneId): Instant {
        val normalized = value.trim()
        return try {
            if (normalized.endsWith("Z", ignoreCase = true) || OFFSET_SUFFIX.containsMatchIn(normalized)) {
                OffsetDateTime.parse(normalized).toInstant()
            } else {
                ScheduleRule.resolveLocal(LocalDateTime.parse(normalized), zoneId)
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("care_datetime_invalid")
        }
    }

    fun zone(value: String?, fallback: ZoneId): ZoneId = try {
        value?.trim()?.takeIf(String::isNotEmpty)?.let(ZoneId::of) ?: fallback
    } catch (_: Exception) {
        throw IllegalArgumentException("care_timezone_invalid")
    }

    private val OFFSET_SUFFIX = Regex("[+-]\\d{2}:\\d{2}$")
}
