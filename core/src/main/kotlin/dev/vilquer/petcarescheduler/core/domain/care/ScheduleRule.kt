package dev.vilquer.petcarescheduler.core.domain.care

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

enum class ScheduleKind { ONE_TIME, CALENDAR_INTERVAL, FIXED_INTERVAL, DAILY_TIMES }

enum class CalendarIntervalUnit { DAY, WEEK, MONTH, YEAR }

data class ScheduleSlot(val sequence: Long, val dueAt: Instant)

/**
 * Deterministic scheduling rule for care plans. Calendar rules retain the
 * plan's wall clock, while fixed intervals always measure elapsed time.
 */
data class ScheduleRule(
    val kind: ScheduleKind,
    val calendarUnit: CalendarIntervalUnit? = null,
    val intervalCount: Long? = null,
    val fixedInterval: Duration? = null,
    val dailyTimes: List<LocalTime> = emptyList(),
    val repetitions: Long? = null,
    val endAt: Instant? = null,
) {
    init {
        require(repetitions == null || repetitions > 0) { "care_schedule_repetitions_invalid" }
        when (kind) {
            ScheduleKind.ONE_TIME -> require(
                calendarUnit == null && intervalCount == null && fixedInterval == null && dailyTimes.isEmpty() &&
                    repetitions == null && endAt == null,
            ) { "care_schedule_one_time_invalid" }

            ScheduleKind.CALENDAR_INTERVAL -> {
                require(calendarUnit != null && intervalCount != null && intervalCount > 0) {
                    "care_schedule_calendar_interval_invalid"
                }
                require(fixedInterval == null && dailyTimes.isEmpty()) { "care_schedule_calendar_interval_invalid" }
            }

            ScheduleKind.FIXED_INTERVAL -> {
                require(fixedInterval != null && !fixedInterval.isNegative && !fixedInterval.isZero) {
                    "care_schedule_fixed_interval_invalid"
                }
                require(fixedInterval >= MIN_FIXED_INTERVAL && fixedInterval <= MAX_FIXED_INTERVAL) {
                    "care_schedule_fixed_interval_out_of_range"
                }
                require(calendarUnit == null && intervalCount == null && dailyTimes.isEmpty()) {
                    "care_schedule_fixed_interval_invalid"
                }
            }

            ScheduleKind.DAILY_TIMES -> {
                require(dailyTimes.isNotEmpty() && dailyTimes == dailyTimes.distinct().sorted()) {
                    "care_schedule_daily_times_invalid"
                }
                require(calendarUnit == null && intervalCount == null && fixedInterval == null) {
                    "care_schedule_daily_times_invalid"
                }
            }
        }
    }

    fun firstOnOrAfter(startAt: Instant, cutoff: Instant, zoneId: ZoneId): ScheduleSlot? {
        val effectiveCutoff = maxOf(startAt, cutoff)
        val candidate = when (kind) {
            ScheduleKind.ONE_TIME -> ScheduleSlot(0, startAt)
            ScheduleKind.FIXED_INTERVAL -> fixedFirstOnOrAfter(startAt, effectiveCutoff)
            ScheduleKind.CALENDAR_INTERVAL -> calendarFirstOnOrAfter(startAt, effectiveCutoff, zoneId)
            ScheduleKind.DAILY_TIMES -> dailyFirstOnOrAfter(startAt, effectiveCutoff, zoneId)
        }
        return candidate.takeIf(::isWithinBounds)
    }

    fun next(startAt: Instant, slot: ScheduleSlot, zoneId: ZoneId): ScheduleSlot? {
        val candidate = when (kind) {
            ScheduleKind.ONE_TIME -> return null
            ScheduleKind.FIXED_INTERVAL -> ScheduleSlot(slot.sequence + 1, slot.dueAt.plus(fixedInterval!!))
            ScheduleKind.CALENDAR_INTERVAL -> calendarSlot(startAt, slot.sequence + 1, zoneId)
            ScheduleKind.DAILY_TIMES -> dailyFirstOnOrAfter(startAt, slot.dueAt.plusNanos(1), zoneId)
        }
        require(candidate.sequence == slot.sequence + 1 && candidate.dueAt.isAfter(slot.dueAt)) {
            "care_schedule_did_not_advance"
        }
        return candidate.takeIf(::isWithinBounds)
    }

    private fun fixedFirstOnOrAfter(startAt: Instant, cutoff: Instant): ScheduleSlot {
        if (!cutoff.isAfter(startAt)) return ScheduleSlot(0, startAt)
        val interval = fixedInterval!!
        var sequence = Duration.between(startAt, cutoff).dividedBy(interval)
        var dueAt = startAt.plus(interval.multipliedBy(sequence))
        if (dueAt.isBefore(cutoff)) {
            sequence += 1
            dueAt = dueAt.plus(interval)
        }
        return ScheduleSlot(sequence, dueAt)
    }

    private fun calendarFirstOnOrAfter(startAt: Instant, cutoff: Instant, zoneId: ZoneId): ScheduleSlot {
        val anchor = startAt.atZone(zoneId).toLocalDateTime()
        val localCutoff = cutoff.atZone(zoneId).toLocalDateTime()
        val count = intervalCount!!
        var sequence = when (calendarUnit!!) {
            CalendarIntervalUnit.DAY -> ChronoUnit.DAYS.between(anchor.toLocalDate(), localCutoff.toLocalDate()) / count
            CalendarIntervalUnit.WEEK -> ChronoUnit.WEEKS.between(anchor.toLocalDate(), localCutoff.toLocalDate()) / count
            CalendarIntervalUnit.MONTH -> ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(localCutoff)) / count
            CalendarIntervalUnit.YEAR -> ChronoUnit.YEARS.between(anchor.toLocalDate(), localCutoff.toLocalDate()) / count
        }.coerceAtLeast(0)

        var candidate = calendarSlot(startAt, sequence, zoneId)
        while (candidate.dueAt.isBefore(cutoff)) candidate = calendarSlot(startAt, ++sequence, zoneId)
        while (sequence > 0) {
            val previous = calendarSlot(startAt, sequence - 1, zoneId)
            if (previous.dueAt.isBefore(cutoff)) break
            candidate = previous
            sequence -= 1
        }
        return candidate
    }

    private fun calendarSlot(startAt: Instant, sequence: Long, zoneId: ZoneId): ScheduleSlot {
        val anchor = startAt.atZone(zoneId).toLocalDateTime()
        val amount = Math.multiplyExact(sequence, intervalCount!!)
        val local = when (calendarUnit!!) {
            CalendarIntervalUnit.DAY -> anchor.plusDays(amount)
            CalendarIntervalUnit.WEEK -> anchor.plusWeeks(amount)
            CalendarIntervalUnit.MONTH -> legacyMonthlySlot(anchor, sequence, amount)
            CalendarIntervalUnit.YEAR -> legacyYearlySlot(anchor, sequence, amount)
        }
        return ScheduleSlot(sequence, resolveLocal(local, zoneId))
    }

    /**
     * LocalDateTime.plusMonths/plusYears clip an invalid day and the legacy
     * generator used the clipped result as the next anchor. Inspecting one
     * Gregorian cycle reproduces that behavior without traversing the plan's
     * complete history.
     */
    private fun legacyMonthlySlot(anchor: LocalDateTime, sequence: Long, amount: Long): LocalDateTime {
        if (sequence == 0L) return anchor
        val step = intervalCount!!
        val cycle = GREGORIAN_MONTH_CYCLE / gcd(GREGORIAN_MONTH_CYCLE, Math.floorMod(step, GREGORIAN_MONTH_CYCLE))
        val inspected = minOf(sequence, cycle).toInt()
        var day = anchor.dayOfMonth
        val anchorMonth = YearMonth.from(anchor)
        for (index in 1..inspected) {
            day = minOf(day, anchorMonth.plusMonths(Math.multiplyExact(index.toLong(), step)).lengthOfMonth())
        }
        val target = anchorMonth.plusMonths(amount)
        return LocalDateTime.of(target.atDay(minOf(day, target.lengthOfMonth())), anchor.toLocalTime())
    }

    private fun legacyYearlySlot(anchor: LocalDateTime, sequence: Long, amount: Long): LocalDateTime {
        if (sequence == 0L) return anchor
        val step = intervalCount!!
        val cycle = GREGORIAN_YEAR_CYCLE / gcd(GREGORIAN_YEAR_CYCLE, Math.floorMod(step, GREGORIAN_YEAR_CYCLE))
        val inspected = minOf(sequence, cycle).toInt()
        var day = anchor.dayOfMonth
        for (index in 1..inspected) {
            val visitedYear = Math.toIntExact(Math.addExact(anchor.year.toLong(), Math.multiplyExact(index.toLong(), step)))
            day = minOf(day, YearMonth.of(visitedYear, anchor.month).lengthOfMonth())
        }
        val targetYear = Math.toIntExact(Math.addExact(anchor.year.toLong(), amount))
        val target = YearMonth.of(targetYear, anchor.month)
        return LocalDateTime.of(target.atDay(minOf(day, target.lengthOfMonth())), anchor.toLocalTime())
    }

    private tailrec fun gcd(left: Long, right: Long): Long =
        if (right == 0L) left.coerceAtLeast(1) else gcd(right, left % right)

    private fun dailyFirstOnOrAfter(startAt: Instant, cutoff: Instant, zoneId: ZoneId): ScheduleSlot {
        val startDate = startAt.atZone(zoneId).toLocalDate()
        val initialTimes = dailyTimes.filter { resolveLocal(LocalDateTime.of(startDate, it), zoneId) >= startAt }
        val baseDate = if (initialTimes.isEmpty()) startDate.plusDays(1) else startDate
        val baseTimes = if (initialTimes.isEmpty()) dailyTimes else initialTimes
        val cutoffDate = cutoff.atZone(zoneId).toLocalDate()
        var date = maxOf(baseDate, cutoffDate)

        while (true) {
            val times = if (date == baseDate) baseTimes else dailyTimes
            times.forEachIndexed { index, time ->
                val dueAt = resolveLocal(LocalDateTime.of(date, time), zoneId)
                if (!dueAt.isBefore(cutoff)) {
                    val days = ChronoUnit.DAYS.between(baseDate, date)
                    val sequence = if (days == 0L) {
                        index.toLong()
                    } else {
                        baseTimes.size.toLong() + (days - 1) * dailyTimes.size + index
                    }
                    return ScheduleSlot(sequence, dueAt)
                }
            }
            date = date.plusDays(1)
        }
    }

    private fun isWithinBounds(slot: ScheduleSlot): Boolean =
        (repetitions == null || slot.sequence < repetitions) && (endAt == null || !slot.dueAt.isAfter(endAt))

    companion object {
        val MIN_FIXED_INTERVAL: Duration = Duration.ofMinutes(1)
        val MAX_FIXED_INTERVAL: Duration = Duration.ofDays(365)
        private const val GREGORIAN_MONTH_CYCLE = 4_800L
        private const val GREGORIAN_YEAR_CYCLE = 400L

        fun oneTime() = ScheduleRule(ScheduleKind.ONE_TIME)

        fun calendar(
            unit: CalendarIntervalUnit,
            intervalCount: Long = 1,
            repetitions: Long? = null,
            endAt: Instant? = null,
        ) = ScheduleRule(ScheduleKind.CALENDAR_INTERVAL, unit, intervalCount, repetitions = repetitions, endAt = endAt)

        fun fixed(interval: Duration, repetitions: Long? = null, endAt: Instant? = null) =
            ScheduleRule(ScheduleKind.FIXED_INTERVAL, fixedInterval = interval, repetitions = repetitions, endAt = endAt)

        fun daily(times: List<LocalTime>, repetitions: Long? = null, endAt: Instant? = null) =
            ScheduleRule(ScheduleKind.DAILY_TIMES, dailyTimes = times, repetitions = repetitions, endAt = endAt)

        /** SHIFT_FORWARD_BY_GAP and EARLIER_OFFSET, as frozen by ADR-002. */
        fun resolveLocal(local: LocalDateTime, zoneId: ZoneId): Instant {
            val rules = zoneId.rules
            val offsets = rules.getValidOffsets(local)
            return when {
                offsets.size == 1 -> ZonedDateTime.ofLocal(local, zoneId, offsets.first()).toInstant()
                offsets.size > 1 -> ZonedDateTime.ofLocal(local, zoneId, offsets.first()).toInstant()
                else -> {
                    val transition = requireNotNull(rules.getTransition(local)) { "care_schedule_zone_transition_invalid" }
                    ZonedDateTime.ofLocal(local.plus(transition.duration), zoneId, transition.offsetAfter).toInstant()
                }
            }
        }
    }
}
