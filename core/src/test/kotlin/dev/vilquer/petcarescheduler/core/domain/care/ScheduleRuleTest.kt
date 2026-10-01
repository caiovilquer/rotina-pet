package dev.vilquer.petcarescheduler.core.domain.care

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ScheduleRuleTest {
    @Test
    fun `twelve hour plan for seven days yields fourteen exact doses`() {
        val start = Instant.parse("2026-07-14T08:00:00Z")
        val rule = ScheduleRule.fixed(Duration.ofHours(12), repetitions = 14)

        val slots = generateSequence(rule.firstOnOrAfter(start, start, ZoneId.of("UTC"))!!) {
            rule.next(start, it, ZoneId.of("UTC"))
        }.toList()

        assertEquals(14, slots.size)
        assertEquals((0L..13L).toList(), slots.map { it.sequence })
        assertEquals(Duration.ofHours(12), Duration.between(slots[12].dueAt, slots[13].dueAt))
    }

    @Test
    fun `fixed interval measures elapsed hours across DST`() {
        val zone = ZoneId.of("America/New_York")
        val start = Instant.parse("2026-03-07T13:00:00Z") // 08:00 local before spring-forward
        val rule = ScheduleRule.fixed(Duration.ofHours(12), repetitions = 4)
        val first = rule.firstOnOrAfter(start, start, zone)!!
        val slots = generateSequence(first) { rule.next(start, it, zone) }.toList()

        assertEquals(
            listOf("2026-03-07T13:00:00Z", "2026-03-08T01:00:00Z", "2026-03-08T13:00:00Z", "2026-03-09T01:00:00Z"),
            slots.map { it.dueAt.toString() },
        )
    }

    @Test
    fun `calendar interval preserves local clock across DST`() {
        val zone = ZoneId.of("America/New_York")
        val start = Instant.parse("2026-03-07T13:00:00Z")
        val rule = ScheduleRule.calendar(CalendarIntervalUnit.DAY, repetitions = 3)
        val slots = generateSequence(rule.firstOnOrAfter(start, start, zone)!!) { rule.next(start, it, zone) }.toList()

        assertEquals(listOf("08:00", "08:00", "08:00"), slots.map { it.dueAt.atZone(zone).toLocalTime().toString() })
        assertEquals(listOf("2026-03-07T13:00:00Z", "2026-03-08T12:00:00Z", "2026-03-09T12:00:00Z"), slots.map { it.dueAt.toString() })
    }

    @Test
    fun `calendar interval preserves legacy clipping without replaying history`() {
        val zone = ZoneId.of("UTC")
        val monthlyStart = Instant.parse("2024-01-31T10:00:00Z")
        val monthly = ScheduleRule.calendar(CalendarIntervalUnit.MONTH, repetitions = 4)
        val monthlySlots = generateSequence(monthly.firstOnOrAfter(monthlyStart, monthlyStart, zone)!!) {
            monthly.next(monthlyStart, it, zone)
        }.toList()
        assertEquals(
            listOf("2024-01-31T10:00:00Z", "2024-02-29T10:00:00Z", "2024-03-29T10:00:00Z", "2024-04-29T10:00:00Z"),
            monthlySlots.map { it.dueAt.toString() },
        )

        val yearlyStart = Instant.parse("2024-02-29T10:00:00Z")
        val yearly = ScheduleRule.calendar(CalendarIntervalUnit.YEAR, repetitions = 5)
        val yearlySlots = generateSequence(yearly.firstOnOrAfter(yearlyStart, yearlyStart, zone)!!) {
            yearly.next(yearlyStart, it, zone)
        }.toList()
        assertEquals("2028-02-28T10:00:00Z", yearlySlots.last().dueAt.toString())
    }

    @Test
    fun `daily times shifts a gap forward and chooses earlier offset in overlap`() {
        val zone = ZoneId.of("America/New_York")
        val springStart = Instant.parse("2026-03-07T00:00:00Z")
        val gapRule = ScheduleRule.daily(listOf(LocalTime.of(2, 30)), repetitions = 2)
        val gapFirst = gapRule.firstOnOrAfter(springStart, springStart, zone)!!
        val gapSecond = gapRule.next(springStart, gapFirst, zone)!!
        assertEquals(LocalTime.of(3, 30), gapSecond.dueAt.atZone(zone).toLocalTime())

        val overlap = ScheduleRule.resolveLocal(LocalDateTime.of(2026, 11, 1, 1, 30), zone)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), overlap)
    }

    @Test
    fun `firstOnOrAfter skips more than five hundred historical slots`() {
        val start = Instant.parse("2020-01-01T00:00:00Z")
        val cutoff = Instant.parse("2026-07-12T10:00:00Z")
        val rule = ScheduleRule.fixed(Duration.ofHours(1))

        val slot = rule.firstOnOrAfter(start, cutoff, ZoneId.of("UTC"))!!

        assertEquals(cutoff, slot.dueAt)
        assertEquals(Duration.between(start, cutoff).toHours(), slot.sequence)
    }

    @Test
    fun `end instant is inclusive`() {
        val start = Instant.parse("2026-07-12T10:00:00Z")
        val rule = ScheduleRule.fixed(Duration.ofHours(12), endAt = start.plus(Duration.ofDays(1)))
        val slots = generateSequence(rule.firstOnOrAfter(start, start, ZoneId.of("UTC"))!!) {
            rule.next(start, it, ZoneId.of("UTC"))
        }.toList()

        assertEquals(3, slots.size)
        assertNull(rule.next(start, slots.last(), ZoneId.of("UTC")))
    }
}
