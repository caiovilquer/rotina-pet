package dev.vilquer.petcarescheduler.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ContextConfiguration

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [PersistenceTestApplication::class])
class PgvectorExtensionIntegrationTest : AbstractPostgresIntegrationTest() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `migrations enable the pinned pgvector extension`() {
        val installedVersion = jdbc.queryForObject(
            "select extversion from pg_extension where extname = 'vector'",
            String::class.java,
        )

        assertEquals("0.8.2", installedVersion)
    }
}
