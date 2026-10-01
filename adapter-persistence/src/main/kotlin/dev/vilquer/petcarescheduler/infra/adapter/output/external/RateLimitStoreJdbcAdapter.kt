package dev.vilquer.petcarescheduler.infra.adapter.output.external

import dev.vilquer.petcarescheduler.usecase.contract.drivenports.RateLimitStorePort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

@Repository
class RateLimitStoreJdbcAdapter(private val jdbc: JdbcTemplate) : RateLimitStorePort {
    override fun registerAttempt(key: String, now: Instant, window: Duration): Int {
        val cutoff = now.minus(window)
        return requireNotNull(
            jdbc.queryForObject(
                """
                insert into rate_limit_attempt (id, count, window_start, version)
                values (?, 1, ?, 0)
                on conflict (id) do update
                   set count = case
                                   when rate_limit_attempt.window_start < ? then 1
                                   else rate_limit_attempt.count + 1
                               end,
                       window_start = case
                                          when rate_limit_attempt.window_start < ? then excluded.window_start
                                          else rate_limit_attempt.window_start
                                      end,
                       version = rate_limit_attempt.version + 1
                returning count
                """.trimIndent(),
                Int::class.java,
                key,
                Timestamp.from(now),
                Timestamp.from(cutoff),
                Timestamp.from(cutoff),
            ),
        )
    }

    override fun delete(key: String) {
        jdbc.update("delete from rate_limit_attempt where id = ?", key)
    }

    override fun deleteOlderThan(cutoff: Instant): Int =
        jdbc.update("delete from rate_limit_attempt where window_start < ?", Timestamp.from(cutoff))
}
