package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.FakeClock
import dev.vilquer.petcarescheduler.application.InMemoryCareOccurrenceRepo
import dev.vilquer.petcarescheduler.application.InMemoryPetRepo
import dev.vilquer.petcarescheduler.application.InMemoryTutorRepo
import dev.vilquer.petcarescheduler.application.exception.NotFoundException
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrence
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceId
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanId
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.Pet
import dev.vilquer.petcarescheduler.core.domain.entity.Tutor
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.valueobject.Email
import dev.vilquer.petcarescheduler.application.TEST_HOUSEHOLD_ID
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdAccess
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class DashboardAppServiceTest {
    private val tutorId = TutorId(1)
    private val access = HouseholdAccess(TEST_HOUSEHOLD_ID, tutorId, HouseholdRole.OWNER)
    private val now = LocalDateTime.of(2026, 7, 12, 9, 0)
    private val clock = FakeClock(ZonedDateTime.of(now, ZoneId.of("America/Sao_Paulo")))

    @Test
    fun `overview returns profile totals pets and only upcoming seven-day events`() {
        val tutors = InMemoryTutorRepo()
        tutors.save(
            Tutor(
                id = tutorId,
                firstName = "Ana",
                lastName = "Silva",
                email = Email.of("ana@example.com").getOrThrow(),
                passwordHash = "hash",
                phoneNumber = null,
                avatar = "https://example.com/ana.jpg",
            ),
        )
        val pets = InMemoryPetRepo()
        val luna = pets.save(
            Pet(name = "Luna", species = "cat", breed = null, birthdate = null, tutorId = tutorId, householdId = TEST_HOUSEHOLD_ID),
        )
        pets.save(Pet(name = "Tobias", species = "dog", breed = null, birthdate = null, tutorId = tutorId, householdId = TEST_HOUSEHOLD_ID))
        val createdAt = clock.now().toInstant()
        fun occurrence(type: EventType, title: String, dueAt: LocalDateTime, status: CareOccurrenceStatus) =
            CareOccurrence(
                id = CareOccurrenceId(UUID.randomUUID()),
                planId = CarePlanId(UUID.randomUUID()),
                scheduleRevision = 0,
                householdId = TEST_HOUSEHOLD_ID,
                tutorId = tutorId,
                petId = luna.id!!,
                responsibleTutorId = tutorId,
                sequence = 0L,
                type = type,
                title = title,
                dueAt = dueAt.atZone(access.zoneId).toInstant(),
                zoneId = access.zoneId,
                status = status,
                completedAt = if (status == CareOccurrenceStatus.COMPLETED) createdAt else null,
                completedByTutorId = if (status == CareOccurrenceStatus.COMPLETED) tutorId else null,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        val occurrences = InMemoryCareOccurrenceRepo(
            listOf(
                occurrence(EventType.VACCINE, "Reforço", now.plusDays(2), CareOccurrenceStatus.SCHEDULED),
                occurrence(EventType.SERVICE, "Banho", now.plusDays(8), CareOccurrenceStatus.SCHEDULED),
                occurrence(EventType.MEDICINE, "Dose", now.plusDays(1), CareOccurrenceStatus.COMPLETED),
            ),
        )

        val result = DashboardAppService(tutors, pets, occurrences, clock).getOverview(access)

        assertEquals("Ana", result.firstName)
        assertEquals("ana@example.com", result.email)
        assertEquals(2, result.totalPets)
        assertEquals(3, result.totalEvents)
        assertEquals(listOf("Luna", "Tobias"), result.pets.map { it.name })
        assertEquals(1, result.upcomingEvents.size)
        assertEquals("Reforço", result.upcomingEvents.single().title)
    }

    @Test
    fun `overview rejects an unknown tutor`() {
        val service = DashboardAppService(
            InMemoryTutorRepo(),
            InMemoryPetRepo(),
            InMemoryCareOccurrenceRepo(),
            clock,
        )

        assertThrows(NotFoundException::class.java) { service.getOverview(access) }
    }
}
