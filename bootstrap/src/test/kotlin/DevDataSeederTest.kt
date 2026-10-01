package dev.vilquer.petcarescheduler.infra

import dev.vilquer.petcarescheduler.PetCareSchedulerApplication
import dev.vilquer.petcarescheduler.core.domain.valueobject.Email
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TutorRepositoryPort
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    classes = [PetCareSchedulerApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "server.ssl.enabled=false",
        "app.mail.from=noreply@test.local",
        "app.seed.enabled=true",
    ],
)
@ActiveProfiles("dev")
class DevDataSeederTest : AbstractPostgresIntegrationTest() {

    @Autowired
    lateinit var tutors: TutorRepositoryPort

    @Test
    fun `seed creates the main shared-family accounts`() {
        assertNotNull(tutors.findByEmail(Email.of("ana.silva@rotinapet.dev").getOrThrow()))
        assertNotNull(tutors.findByEmail(Email.of("bruno.costa@rotinapet.dev").getOrThrow()))
        assertNotNull(tutors.findByEmail(Email.of("diego.ramos@rotinapet.dev").getOrThrow()))
        assertTrue(tutors.countAll() >= 6)
    }
}
