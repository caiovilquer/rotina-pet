package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.*
import dev.vilquer.petcarescheduler.application.exception.ForbiddenException
import dev.vilquer.petcarescheduler.core.domain.care.*
import dev.vilquer.petcarescheduler.core.domain.entity.*
import dev.vilquer.petcarescheduler.core.domain.finance.*
import dev.vilquer.petcarescheduler.core.domain.health.*
import dev.vilquer.petcarescheduler.core.domain.household.*
import dev.vilquer.petcarescheduler.usecase.command.*
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.*

class FinanceAppServiceTest {
    private val owner = TutorId(1)
    private val petId = PetId(10)
    private val access = HouseholdAccess(TEST_HOUSEHOLD_ID, owner, HouseholdRole.OWNER)
    private val clock = FakeClock(ZonedDateTime.of(2026, 7, 12, 12, 0, 0, 0, ZoneId.of("America/Sao_Paulo")))

    @Test
    fun `overview separates realized clinical and forecast costs while viewer is denied`() {
        val expenses = Expenses()
        val health = InMemoryHealthRecordRepo()
        val petRepo = InMemoryPetRepo(mapOf(petId to Pet(petId, 0, "Nina", "Gato", null, null, tutorId = owner, householdId = TEST_HOUSEHOLD_ID)))
        val occurrence = CareOccurrence(
            CareOccurrenceId(java.util.UUID.randomUUID()), planId = CarePlanId(java.util.UUID.randomUUID()), scheduleRevision = 0,
            householdId = TEST_HOUSEHOLD_ID, tutorId = owner, petId = petId, responsibleTutorId = owner, sequence = 0L,
            type = EventType.SERVICE, title = "Banho", dueAt = Instant.parse("2026-07-20T13:00:00Z"), zoneId = access.zoneId,
            status = CareOccurrenceStatus.SCHEDULED, estimatedCostAmount = BigDecimal("80.00"), estimatedCostCurrency = "BRL",
            createdAt = clock.now().toInstant(), updatedAt = clock.now().toInstant(),
        )
        val service = FinanceAppService(expenses, health, InMemoryCareOccurrenceRepo(listOf(occurrence)), petRepo, FakeTransactionPort(), clock)
        service.create(CreateExpenseCommand(petId, ExpenseCategory.FOOD, "Ração", BigDecimal("100.00"), "BRL", Instant.parse("2026-07-05T12:00:00Z"), null), access)
        health.save(HealthRecord(
            householdId = TEST_HOUSEHOLD_ID, tutorId = owner, petId = petId, type = HealthRecordType.CONSULTATION,
            occurredAt = Instant.parse("2026-07-06T12:00:00Z"), title = "Consulta", costAmount = BigDecimal("150.00"), currency = "BRL",
            createdByTutorId = owner, createdAt = clock.now().toInstant(), updatedAt = clock.now().toInstant(),
        ))

        val result = service.overview(FinanceOverviewQuery(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 12), LocalDate.of(2026, 8, 11)), access)
        assertEquals(BigDecimal("100.00"), result.realized.single().expenses)
        assertEquals(BigDecimal("150.00"), result.realized.single().clinical)
        assertEquals(BigDecimal("250.00"), result.realized.single().total)
        assertEquals(BigDecimal("80.00"), result.forecast.single().amount)
        assertThrows(ForbiddenException::class.java) {
            service.overview(FinanceOverviewQuery(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 12), LocalDate.of(2026, 8, 11)), HouseholdAccess(TEST_HOUSEHOLD_ID, TutorId(2), HouseholdRole.VIEWER))
        }
    }

    private class Expenses : ExpenseRepositoryPort {
        private val items = linkedMapOf<ExpenseId, Expense>()
        override fun save(expense: Expense): Expense = expense.copy(version = (items[expense.id]?.version ?: -1) + 1).also { items[it.id] = it }
        override fun findByIdAndHousehold(id: ExpenseId, householdId: HouseholdId) = items[id]?.takeIf { it.householdId == householdId }
        override fun findByIdAndHouseholdForUpdate(id: ExpenseId, householdId: HouseholdId) = findByIdAndHousehold(id, householdId)
        override fun search(householdId: HouseholdId, filter: ExpenseFilter, page: Int, size: Int) = items.values.filter {
            it.householdId == householdId && !it.occurredAt.isBefore(filter.from) && it.occurredAt < filter.to &&
                (filter.petId == null || it.petId == filter.petId) && (filter.category == null || it.category == filter.category)
        }.drop(page * size).take(size)
        override fun count(householdId: HouseholdId, filter: ExpenseFilter) = search(householdId, filter, 0, Int.MAX_VALUE).size.toLong()
        override fun delete(id: ExpenseId) { items.remove(id) }
    }
}
