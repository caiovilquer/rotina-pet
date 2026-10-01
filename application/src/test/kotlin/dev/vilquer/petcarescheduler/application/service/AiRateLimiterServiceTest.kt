package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.RateLimitException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.RateLimitStorePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AiRateLimiterServiceTest {
    @Test
    fun `pilot allows twenty questions per tutor household and rejects the next one`() {
        val limiter = RateLimiterService(RateLimitProperties(), Store())
        repeat(20) { limiter.check(RateLimitAction.ASSISTANT_QUESTION, "household:tutor") }

        val error = assertThrows(RateLimitException::class.java) {
            limiter.check(RateLimitAction.ASSISTANT_QUESTION, "household:tutor")
        }

        assertEquals("AI_RATE_LIMITED", error.publicCode)
    }

    private class Store : RateLimitStorePort {
        private val counts = mutableMapOf<String, Int>()
        override fun registerAttempt(key: String, now: Instant, window: Duration) =
            counts.compute(key) { _, current -> (current ?: 0) + 1 }!!
        override fun delete(key: String) { counts.remove(key) }
        override fun deleteOlderThan(cutoff: Instant) = 0
    }
}
