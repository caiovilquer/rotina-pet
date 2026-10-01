package petcarescheduler.infra.test

import dev.vilquer.petcarescheduler.infra.AbstractPostgresIntegrationTest
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class CareScheduleMigrationIntegrationTest : AbstractPostgresIntegrationTest() {
    @Test
    fun `v21 backfills calendar rule canonical instants long sequence and next cursor`() {
        val fixture = fixture("UTC", LocalDateTime.of(2026, 7, 14, 9, 0))
        try {
            fixture.migrateLatest()

            assertEquals("CALENDAR_INTERVAL", fixture.jdbc.queryForObject("select schedule_kind from care_plan", String::class.java))
            assertEquals("UTC", fixture.jdbc.queryForObject("select zone_id from care_plan", String::class.java))
            assertEquals(Instant.parse("2026-07-14T09:00:00Z"), fixture.jdbc.queryForObject("select start_at_instant from care_plan", Timestamp::class.java)!!.toInstant())
            assertEquals(Instant.parse("2026-07-14T09:00:00Z"), fixture.jdbc.queryForObject("select due_at_instant from care_occurrence", Timestamp::class.java)!!.toInstant())
            assertEquals(0L, fixture.jdbc.queryForObject("select sequence_number from care_occurrence", Long::class.java)!!)
            assertEquals(1L, fixture.jdbc.queryForObject("select next_sequence from care_plan_materialization_cursor", Long::class.java)!!)
            assertEquals(
                Instant.parse("2026-07-15T09:00:00Z"),
                fixture.jdbc.queryForObject("select next_due_at_instant from care_plan_materialization_cursor", Timestamp::class.java)!!.toInstant(),
            )
            assertEquals("ACTIVE", fixture.jdbc.queryForObject("select status from care_plan_materialization_cursor", String::class.java))
        } finally {
            fixture.drop()
        }
    }

    @Test
    fun `v21 reports ambiguous backfill and rolls the migration back`() {
        val fixture = fixture("America/New_York", LocalDateTime.of(2026, 11, 1, 1, 30))
        try {
            val failure = assertThrows(Exception::class.java) { fixture.migrateLatest() }

            assertTrue(failure.message.orEmpty().contains("care schedule v2 backfill refused"))
            assertFalse(fixture.jdbc.queryForObject(
                """select exists(select 1 from information_schema.columns
                    where table_schema = current_schema() and table_name = 'care_plan' and column_name = 'schedule_kind')""",
                Boolean::class.java,
            )!!)
            assertEquals(0, fixture.jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '21' and success = true",
                Int::class.java,
            )!!)
        } finally {
            fixture.drop()
        }
    }

    private fun fixture(zoneId: String, local: LocalDateTime): MigrationFixture {
        val schema = "schedule_${UUID.randomUUID().toString().replace("-", "")}"
        val separator = if (postgres.jdbcUrl.contains('?')) "&" else "?"
        val dataSource = DriverManagerDataSource(
            "${postgres.jdbcUrl}${separator}currentSchema=$schema",
            postgres.username,
            postgres.password,
        )
        val base = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
            .schemas(schema)
            .defaultSchema(schema)
            .target(MigrationVersion.fromVersion("20"))
            .load()
        base.migrate()
        val jdbc = JdbcTemplate(dataSource)
        seedLegacySchedule(jdbc, zoneId, local)
        return MigrationFixture(schema, dataSource, jdbc)
    }

    private fun seedLegacySchedule(jdbc: JdbcTemplate, zoneId: String, local: LocalDateTime) {
        val tutorId = jdbc.queryForObject(
            "insert into tutor(first_name, email, password_hash, password_changed_at) values ('Ana', ?, 'hash', current_timestamp) returning id",
            Long::class.java,
            "migration-${UUID.randomUUID()}@example.com",
        )!!
        val householdId = UUID.randomUUID()
        jdbc.update(
            "insert into household(id, name, created_by_tutor_id, created_at, updated_at, timezone) values (?, 'Casa', ?, current_timestamp, current_timestamp, ?)",
            householdId, tutorId, zoneId,
        )
        val petId = jdbc.queryForObject(
            "insert into pet(name, species, tutor_id, household_id) values ('Luna', 'cat', ?, ?) returning id",
            Long::class.java,
            tutorId, householdId,
        )!!
        val planId = UUID.randomUUID()
        jdbc.update(
            """insert into care_plan(
                id, schedule_revision, tutor_id, pet_id, responsible_tutor_id, household_id,
                type, title, start_at, frequency, interval_count, repetitions,
                reminder_minutes_before, critical, active, created_at, updated_at
            ) values (?, 0, ?, ?, ?, ?, 'MEDICINE', 'Dose', ?, 'DAILY', 1, 10, 0, false, true, current_timestamp, current_timestamp)""",
            planId, tutorId, petId, tutorId, householdId, local,
        )
        jdbc.update(
            """insert into care_occurrence(
                id, plan_id, schedule_revision, tutor_id, pet_id, responsible_tutor_id, household_id,
                sequence_number, type, title, due_at, status, critical, created_at, updated_at
            ) values (?, ?, 0, ?, ?, ?, ?, 0, 'MEDICINE', 'Dose', ?, 'SCHEDULED', false, current_timestamp, current_timestamp)""",
            UUID.randomUUID(), planId, tutorId, petId, tutorId, householdId, local,
        )
    }

    private inner class MigrationFixture(
        private val schema: String,
        private val dataSource: DriverManagerDataSource,
        val jdbc: JdbcTemplate,
    ) {
        fun migrateLatest() = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
            .schemas(schema)
            .defaultSchema(schema)
            .load()
            .migrate()

        fun drop() {
            JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
                .execute("drop schema if exists $schema cascade")
        }
    }
}
