package dev.vilquer.petcarescheduler.infra

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Container Postgres único por JVM de teste (padrão "singleton container" do
 * Testcontainers): sobe uma vez e é compartilhado por todas as subclasses,
 * em vez de um container por classe de teste. O Ryuk do Testcontainers
 * encerra o container ao fim da JVM.
 */
abstract class AbstractPostgresIntegrationTest {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:0.8.2-pg16-bookworm")
                    .asCompatibleSubstituteFor("postgres"),
            ).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun registerDatasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.locations") {
                "classpath:db/migration/common,classpath:db/migration/postgresql"
            }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
