package petcarescheduler.infra.test

import dev.vilquer.petcarescheduler.core.domain.care.CarePlan
import dev.vilquer.petcarescheduler.core.domain.care.CarePlanMaterializationStatus
import dev.vilquer.petcarescheduler.core.domain.care.ScheduleRule
import dev.vilquer.petcarescheduler.core.domain.entity.EventType
import dev.vilquer.petcarescheduler.core.domain.entity.PetId
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.infra.AbstractPostgresIntegrationTest
import dev.vilquer.petcarescheduler.infra.PersistenceTestApplication
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.HouseholdJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.PetJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity.TutorJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.mappers.toDomain
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.mappers.toJpa
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.CareOccurrenceJpaRepository
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.CarePlanJpaRepository
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.CarePlanMaterializationCursorJpaRepository
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.HouseholdJpaRepository
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.PetJpaRepository
import dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.repository.TutorJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [PersistenceTestApplication::class])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CareScheduleCursorConcurrencyIntegrationTest : AbstractPostgresIntegrationTest() {
    @Autowired lateinit var tutors: TutorJpaRepository
    @Autowired lateinit var households: HouseholdJpaRepository
    @Autowired lateinit var pets: PetJpaRepository
    @Autowired lateinit var plans: CarePlanJpaRepository
    @Autowired lateinit var cursors: CarePlanMaterializationCursorJpaRepository
    @Autowired lateinit var occurrences: CareOccurrenceJpaRepository
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `two workers lock plan then cursor and materialize each global sequence once`() {
        val plan = persistedPlan(repetitions = 20)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val workers = (1..2).map {
                CompletableFuture.runAsync({
                    ready.countDown()
                    start.await()
                    materialize(plan, maxOccurrences = 20)
                }, pool)
            }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()
            workers.forEach { it.get(20, TimeUnit.SECONDS) }

            val saved = occurrences.findAll().filter { it.planId == plan.id.value }
            assertEquals(20, saved.size)
            assertEquals((0L..19L).toList(), saved.sortedBy { it.sequence }.map { it.sequence })
            assertEquals(
                CarePlanMaterializationStatus.EXHAUSTED,
                cursors.findByPlanIdAndScheduleRevision(plan.id.value, 0)!!.status,
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `failure after occurrence insert rolls back cursor and retry recreates the same ids`() {
        val plan = persistedPlan(repetitions = 3)
        val expectedIds = plan.materialize(plan.initialCursor(plan.startAt, NOW), HORIZON, NOW).occurrences.map { it.id.value }

        assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                val lockedPlan = plans.findByHouseholdForUpdate(plan.id.value, plan.householdId.value)!!.toDomain()
                val cursor = cursors.findForUpdate(plan.id.value, 0)!!.toDomain()
                val batch = lockedPlan.materialize(cursor, HORIZON, NOW)
                occurrences.saveAllAndFlush(batch.occurrences.map { it.toJpa() })
                throw IllegalStateException("simulated_failure_before_cursor_advance")
            }
        }

        assertEquals(0, occurrences.findAll().count { it.planId == plan.id.value })
        assertEquals(0L, cursors.findByPlanIdAndScheduleRevision(plan.id.value, 0)!!.nextSequence)

        materialize(plan, maxOccurrences = 3)
        assertEquals(expectedIds, occurrences.findAll().filter { it.planId == plan.id.value }.sortedBy { it.sequence }.map { it.id })
    }

    private fun materialize(plan: CarePlan, maxOccurrences: Int) {
        TransactionTemplate(transactionManager).executeWithoutResult {
            val lockedPlan = plans.findByHouseholdForUpdate(plan.id.value, plan.householdId.value)!!.toDomain()
            val cursor = cursors.findForUpdate(plan.id.value, plan.scheduleRevision)!!.toDomain()
            val batch = lockedPlan.materialize(cursor, HORIZON, NOW, maxOccurrences)
            occurrences.saveAllAndFlush(batch.occurrences.map { it.toJpa() })
            cursors.saveAndFlush(batch.cursor.toJpa())
        }
    }

    private fun persistedPlan(repetitions: Long): CarePlan {
        val tutor = tutors.saveAndFlush(TutorJpa().also {
            it.firstName = "Tutor"
            it.email = "cursor-${UUID.randomUUID()}@example.com"
            it.passwordHash = "hash"
        })
        val household = households.saveAndFlush(HouseholdJpa().also {
            it.id = UUID.randomUUID()
            it.name = "Casa cursor"
            it.createdByTutorId = tutor.id!!
            it.timezone = "UTC"
            it.createdAt = NOW
            it.updatedAt = NOW
        })
        val pet = pets.saveAndFlush(PetJpa().also {
            it.name = "Luna"
            it.species = "cat"
            it.tutorId = tutor.id
            it.householdId = household.id
        })
        val plan = CarePlan(
            householdId = HouseholdId(household.id),
            tutorId = TutorId(tutor.id!!),
            petId = PetId(pet.id!!),
            responsibleTutorId = TutorId(tutor.id!!),
            type = EventType.MEDICINE,
            title = "Dose",
            startAt = NOW,
            zoneId = ZoneId.of("UTC"),
            scheduleRule = ScheduleRule.fixed(Duration.ofHours(12), repetitions),
            createdAt = NOW,
            updatedAt = NOW,
        )
        plans.saveAndFlush(plan.toJpa())
        cursors.saveAndFlush(plan.initialCursor(plan.startAt, NOW).toJpa())
        return plan
    }

    companion object {
        private val NOW = Instant.parse("2026-07-14T12:00:00Z")
        private val HORIZON = NOW.plus(Duration.ofDays(30))
    }
}
