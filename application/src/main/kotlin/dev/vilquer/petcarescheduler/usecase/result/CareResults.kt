package dev.vilquer.petcarescheduler.usecase.result

import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleKind
import dev.vilquer.petcarescheduler.core.domain.care.CalendarIntervalUnit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.math.BigDecimal
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdTimezone

data class CarePlanResult(
    val id: UUID,
    val version: Long?,
    val petId: Long,
    val responsibleTutorId: Long,
    val type: EventType,
    val title: String,
    val instructions: String?,
    val startAt: Instant,
    val startAtLocal: LocalDateTime,
    val scheduleRule: CareScheduleRuleResult,
    val reminderMinutesBefore: Int,
    val critical: Boolean,
    val escalationDelayMinutes: Int?,
    val escalationTutorId: Long?,
    val estimatedCostAmount: BigDecimal?,
    val estimatedCostCurrency: String?,
    val active: Boolean,
    val timezone: String = HouseholdTimezone.DEFAULT_ID,
)

data class CareScheduleRuleResult(
    val kind: ScheduleKind,
    val calendarUnit: CalendarIntervalUnit?,
    val intervalCount: Long?,
    val fixedIntervalMinutes: Long?,
    val dailyTimes: List<String>,
    val repetitions: Long?,
    val endAt: Instant?,
)

data class CarePlansPageResult(
    val items: List<CarePlanResult>,
    val total: Long,
    val page: Int,
    val size: Int,
)

data class CareOccurrenceResult(
    val id: UUID,
    val version: Long?,
    val planId: UUID,
    val petId: Long,
    val responsibleTutorId: Long,
    val type: EventType,
    val title: String,
    val instructions: String?,
    val dueAt: Instant,
    val dueAtLocal: LocalDateTime,
    val status: CareOccurrenceStatus,
    val completedAt: Instant?,
    val completedByTutorId: Long?,
    val completionNote: String?,
    val critical: Boolean,
    val escalationDelayMinutes: Int?,
    val escalationTutorId: Long?,
    val estimatedCostAmount: BigDecimal?,
    val estimatedCostCurrency: String?,
    val canUndoUntil: Instant?,
    val timezone: String = HouseholdTimezone.DEFAULT_ID,
)

data class CareOccurrencesPageResult(
    val items: List<CareOccurrenceResult>,
    val total: Long,
    val page: Int,
    val size: Int,
)

data class TodayCareResult(
    val date: LocalDate,
    val overdue: List<CareOccurrenceResult>,
    val today: List<CareOccurrenceResult>,
    val completedToday: List<CareOccurrenceResult>,
    val upcomingSevenDays: Long,
    val timezone: String = HouseholdTimezone.DEFAULT_ID,
)
