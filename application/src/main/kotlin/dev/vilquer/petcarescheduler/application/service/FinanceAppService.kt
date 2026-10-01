package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.ConflictException
import dev.vilquer.petcarescheduler.application.exception.ForbiddenException
import dev.vilquer.petcarescheduler.application.exception.NotFoundException
import dev.vilquer.petcarescheduler.core.domain.care.CareOccurrenceStatus
import dev.vilquer.petcarescheduler.core.domain.finance.Expense
import dev.vilquer.petcarescheduler.core.domain.finance.ExpenseId
import dev.vilquer.petcarescheduler.core.domain.household.*
import dev.vilquer.petcarescheduler.usecase.command.*
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.*
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.ExpenseUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.FinanceOverviewUseCase
import dev.vilquer.petcarescheduler.usecase.result.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.YearMonth

class FinanceAppService(
    private val expenses: ExpenseRepositoryPort,
    private val healthRecords: HealthRecordRepositoryPort,
    private val occurrences: CareOccurrenceRepositoryPort,
    private val pets: PetRepositoryPort,
    private val transaction: TransactionPort,
    private val clock: ClockPort,
) : ExpenseUseCase, FinanceOverviewUseCase {

    override fun create(command: CreateExpenseCommand, access: HouseholdAccess): ExpenseResult {
        requireFinance(access)
        requirePet(command.petId, access)
        validateOccurredAt(command.occurredAt)
        val now = clock.now().toInstant()
        return expenses.save(Expense(
            householdId = access.householdId,
            petId = command.petId,
            category = command.category,
            description = command.description.trim(),
            amount = command.amount,
            currency = command.currency.trim().uppercase(),
            occurredAt = command.occurredAt,
            notes = optional(command.notes),
            createdByTutorId = access.actorTutorId,
            createdAt = now,
            updatedAt = now,
        )).toResult()
    }

    override fun update(command: UpdateExpenseCommand, access: HouseholdAccess): ExpenseResult = transaction.execute {
        requireFinance(access)
        val current = expenses.findByIdAndHouseholdForUpdate(command.expenseId, access.householdId)
            ?: throw NotFoundException("Despesa não encontrada")
        if (current.version != command.expectedVersion) throw ConflictException("Esta despesa foi alterada. Atualize e tente novamente")
        validateOccurredAt(command.occurredAt)
        expenses.save(current.copy(
            category = command.category,
            description = command.description.trim(),
            amount = command.amount,
            currency = command.currency.trim().uppercase(),
            occurredAt = command.occurredAt,
            notes = optional(command.notes),
            updatedAt = clock.now().toInstant(),
        )).toResult()
    }

    override fun search(query: SearchExpensesQuery, access: HouseholdAccess): ExpensesPageResult {
        requireFinance(access)
        require(query.page >= 0 && query.size in 1..100) { "expense_page_invalid" }
        validatePeriod(query.from, query.to, MAX_ACTUAL_PERIOD)
        query.petId?.let { requirePet(it, access) }
        val filter = ExpenseFilter(query.from, query.to, query.petId, query.category)
        return ExpensesPageResult(
            expenses.search(access.householdId, filter, query.page, query.size).map { it.toResult() },
            expenses.count(access.householdId, filter), query.page, query.size,
        )
    }

    override fun delete(id: ExpenseId, expectedVersion: Long, access: HouseholdAccess) {
        requireFinance(access)
        transaction.execute {
            val current = expenses.findByIdAndHouseholdForUpdate(id, access.householdId)
                ?: throw NotFoundException("Despesa não encontrada")
            if (current.version != expectedVersion) throw ConflictException("Esta despesa foi alterada. Atualize e tente novamente")
            expenses.delete(id)
        }
    }

    override fun overview(query: FinanceOverviewQuery, access: HouseholdAccess): FinanceOverviewResult {
        requireFinance(access)
        require(!query.to.isBefore(query.from)) { "finance_period_invalid" }
        require(Duration.between(query.from.atStartOfDay(), query.to.plusDays(1).atStartOfDay()) <= MAX_OVERVIEW_PERIOD) {
            "finance_period_too_large"
        }
        val localNow = clock.now(access.zoneId)
        require(!query.forecastTo.isBefore(localNow.toLocalDate()) && !query.forecastTo.isAfter(localNow.toLocalDate().plusDays(90))) {
            "finance_forecast_period_invalid"
        }
        query.petId?.let { requirePet(it, access) }
        val zone = access.zoneId
        val fromInstant = query.from.atStartOfDay(zone).toInstant()
        val toInstant = query.to.plusDays(1).atStartOfDay(zone).toInstant()
        val expenseItems = expenses.search(access.householdId, ExpenseFilter(fromInstant, toInstant, query.petId), 0, MAX_ITEMS)
        val clinical = healthRecords.searchCostsByHousehold(access.householdId, query.petId, fromInstant, toInstant, MAX_ITEMS)
            .filter { it.costAmount != null && it.currency != null }
        val forecastStart = localNow.toInstant()
        val forecastEnd = query.forecastTo.plusDays(1).atStartOfDay(zone).toInstant()
        val forecast = occurrences.searchByHousehold(
            access.householdId,
            CareOccurrenceFilter(forecastStart, forecastEnd, query.petId, status = CareOccurrenceStatus.SCHEDULED),
            0, MAX_ITEMS,
        ).filter { it.estimatedCostAmount != null && it.estimatedCostCurrency != null }

        val expenseByCurrency = expenseItems.groupBy { it.currency }.mapValues { (_, items) -> items.sumOf { it.amount } }
        val clinicalByCurrency = clinical.groupBy { it.currency!! }.mapValues { (_, items) -> items.sumOf { it.costAmount!! } }
        val currencies = (expenseByCurrency.keys + clinicalByCurrency.keys).sorted()
        val realized = currencies.map { currency ->
            val direct = expenseByCurrency[currency] ?: ZERO
            val health = clinicalByCurrency[currency] ?: ZERO
            RealizedMoneyResult(currency, direct, health, direct + health)
        }
        val forecastTotals = forecast.groupBy { it.estimatedCostCurrency!! }.map { (currency, items) ->
            MoneyTotalResult(currency, items.sumOf { it.estimatedCostAmount!! })
        }.sortedBy { it.currency }
        val categories = expenseItems.groupBy { it.category.name to it.currency }.map { (key, items) ->
            CategoryCostResult(key.first, key.second, items.sumOf { it.amount })
        } + clinical.groupBy { "CLINICAL" to it.currency!! }.map { (key, items) ->
            CategoryCostResult(key.first, key.second, items.sumOf { it.costAmount!! })
        }
        val monthly = buildList {
            expenseItems.groupBy { YearMonth.from(it.occurredAt.atZone(zone)) to it.currency }.forEach { (key, items) ->
                add(MonthlyCostResult(key.first, key.second, items.sumOf { it.amount }))
            }
            clinical.groupBy { YearMonth.from(it.occurredAt.atZone(zone)) to it.currency!! }.forEach { (key, items) ->
                add(MonthlyCostResult(key.first, key.second, items.sumOf { it.costAmount!! }))
            }
        }.groupBy { it.month to it.currency }.map { (key, items) ->
            MonthlyCostResult(key.first, key.second, items.sumOf { it.amount })
        }.sortedWith(compareBy({ it.month }, { it.currency }))
        val upcoming = forecast.sortedBy { it.dueAt }.take(50).map {
            ForecastCostResult(it.id.value, it.petId.value, it.title, it.dueAt, it.estimatedCostAmount!!, it.estimatedCostCurrency!!)
        }
        return FinanceOverviewResult(
            query.from, query.to, query.forecastTo, realized, forecastTotals,
            categories.sortedWith(compareBy({ it.currency }, { -it.amount.toDouble() })), monthly, upcoming,
            insights(realized, categories, forecast, localNow.toInstant().plus(Duration.ofDays(7))),
        )
    }

    private fun insights(
        realized: List<RealizedMoneyResult>,
        categories: List<CategoryCostResult>,
        forecast: List<dev.vilquer.petcarescheduler.core.domain.care.CareOccurrence>,
        sevenDays: Instant,
    ): List<FinancialInsightResult> = buildList {
        realized.forEach { total ->
            if (total.total > ZERO) categories.filter { it.currency == total.currency }.maxByOrNull { it.amount }?.let { top ->
                val share = top.amount.multiply(BigDecimal(100)).divide(total.total, 0, RoundingMode.HALF_UP)
                if (share >= BigDecimal(60)) add(FinancialInsightResult(
                    "CATEGORY_CONCENTRATION", "${share.toPlainString()}% dos custos em ${total.currency} no período estão em ${categoryLabel(top.category)}.",
                ))
            }
        }
        forecast.groupBy { it.estimatedCostCurrency!! }.forEach { (currency, items) ->
            val total = items.sumOf { it.estimatedCostAmount!! }
            val near = items.filter { it.dueAt.isBefore(sevenDays) }.sumOf { it.estimatedCostAmount!! }
            if (total > ZERO && near > ZERO) {
                val share = near.multiply(BigDecimal(100)).divide(total, 0, RoundingMode.HALF_UP)
                if (share >= BigDecimal(60)) add(FinancialInsightResult(
                    "UPCOMING_CONCENTRATION", "${share.toPlainString()}% da previsão em $currency está concentrada nos próximos 7 dias.",
                ))
            }
        }
    }

    private fun requireFinance(access: HouseholdAccess) {
        if (!access.can(HouseholdPermission.MANAGE_FINANCES)) throw ForbiddenException("Somente proprietários podem acessar os dados financeiros")
    }
    private fun requirePet(id: dev.vilquer.petcarescheduler.core.domain.entity.PetId, access: HouseholdAccess) =
        pets.findByIdAndHousehold(id, access.householdId) ?: throw NotFoundException("Pet não encontrado")
    private fun validateOccurredAt(value: Instant) { require(!value.isAfter(clock.now().toInstant().plusSeconds(300))) { "expense_date_in_future" } }
    private fun validatePeriod(from: Instant, to: Instant, max: Duration) {
        require(to.isAfter(from) && Duration.between(from, to) <= max) { "expense_period_invalid" }
    }
    private fun optional(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }
    private fun categoryLabel(value: String) = when (value) {
        "CLINICAL" -> "registros clínicos"
        "VETERINARY" -> "atendimento veterinário"
        "MEDICATION" -> "medicamentos"
        "VACCINE" -> "vacinas"
        "EXAM" -> "exames"
        "FOOD" -> "alimentação"
        "HYGIENE" -> "higiene"
        "SERVICE" -> "serviços"
        "INSURANCE" -> "plano ou seguro"
        else -> "outros custos"
    }
    private fun Expense.toResult() = ExpenseResult(id.value, version, petId.value, category, description, amount, currency, occurredAt, notes, createdByTutorId.value)

    companion object {
        private val ZERO = BigDecimal.ZERO.setScale(2)
        private val MAX_ACTUAL_PERIOD = Duration.ofDays(366L * 5)
        private val MAX_OVERVIEW_PERIOD = Duration.ofDays(366)
        private const val MAX_ITEMS = 5_000
    }
}
