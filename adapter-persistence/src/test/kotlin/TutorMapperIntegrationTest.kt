package petcarescheduler.infra.test

import dev.vilquer.petcarescheduler.infra.AbstractPostgresIntegrationTest
import dev.vilquer.petcarescheduler.infra.PersistenceTestApplication
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.mappers.TutorMapper
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.TutorJpaRepository
import dev.vilquer.petcarescheduler.core.domain.entity.Tutor
import dev.vilquer.petcarescheduler.core.domain.valueobject.Email
import dev.vilquer.petcarescheduler.core.domain.valueobject.PhoneNumber
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ContextConfiguration

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [PersistenceTestApplication::class])
class TutorMapperIntegrationTest : AbstractPostgresIntegrationTest() {

    @Autowired
    private lateinit var tutorRepository: TutorJpaRepository

    @Test
    fun `should correctly map Tutor between domain and persistence layers`() {
        val originalTutor = createTestTutor()

        val persistedTutor = persistAndRetrieveTutor(originalTutor)

        assertTutorMappedCorrectly(originalTutor, persistedTutor)
    }

    private fun createTestTutor(): Tutor {
        // id null: a sequence do banco é compartilhada entre os testes do mesmo
        // contexto, então qualquer id pré-fixado quebra a FK criada na V5
        return Tutor(
            id = null,
            firstName = "Carlos",
            lastName = "Mendes",
            email = Email.of("carlos@ex.com").getOrThrow(),
            passwordHash = "hash123",
            phoneNumber = PhoneNumber.of("1199999-0000").getOrNull(),
            avatar = null
        )
    }

    private fun persistAndRetrieveTutor(domainTutor: Tutor): Tutor {
        val tutorJpa = TutorMapper.toJpa(domainTutor)
        val savedJpa = tutorRepository.save(tutorJpa)
        val fetchedJpa = tutorRepository.findById(savedJpa.id!!).orElseThrow()
        return TutorMapper.toDomain(fetchedJpa)
    }

    private fun assertTutorMappedCorrectly(original: Tutor, persisted: Tutor) {
        assertNotNull(persisted.id, "Tutor should receive an ID after saving")
        assertEquals(original.firstName, persisted.firstName)
        assertEquals(original.lastName, persisted.lastName)
        assertEquals(original.email, persisted.email)
        assertEquals(original.passwordHash, persisted.passwordHash)
        assertEquals(original.phoneNumber, persisted.phoneNumber)
    }
}
