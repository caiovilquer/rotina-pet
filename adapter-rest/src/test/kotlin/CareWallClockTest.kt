package dev.vilquer.petcarescheduler.application.adapter.input.rest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class CareWallClockTest {
    @Test
    fun `naive input resolves to an instant in the plan zone`() {
        assertEquals(
            Instant.parse("2026-07-13T13:00:00Z"),
            CareWallClock.parse("2026-07-13T09:00:00", ZoneId.of("America/New_York")),
        )
    }

    @Test
    fun `offset input remains the canonical instant`() {
        assertEquals(
            Instant.parse("2026-07-13T13:00:00Z"),
            CareWallClock.parse("2026-07-13T13:00:00Z", ZoneId.of("America/New_York")),
        )
        assertEquals(
            Instant.parse("2026-01-13T14:00:00Z"),
            CareWallClock.parse("2026-01-13T14:00:00Z", ZoneId.of("America/New_York")),
        )
    }
}
