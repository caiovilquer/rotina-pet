package dev.vilquer.petcarescheduler.infra

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * PostgreSQL/pgvector único por JVM de teste. Smoke e seeder usam o mesmo
 * dialeto, migrations e extensão exigidos em desenvolvimento e produção.
 */
abstract class AbstractPostgresIntegrationTest {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg16-bookworm")
                .asCompatibleSubstituteFor("postgres"),
        ).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun registerDatasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.locations") {
                "classpath:db/migration/common,classpath:db/migration/postgresql"
            }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
