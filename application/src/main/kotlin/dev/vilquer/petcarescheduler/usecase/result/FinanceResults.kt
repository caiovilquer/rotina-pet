package dev.vilquer.petcarescheduler.usecase.result

import dev.vilquer.petcarescheduler.core.domain.finance.ExpenseCategory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.UUID

data class ExpenseResult(
    val id: UUID,
    val version: Long?,
    val petId: Long,
    val category: ExpenseCategory,
    val description: String,
    val amount: BigDecimal,
    val currency: String,
    val occurredAt: Instant,
    val notes: String?,
    val createdByTutorId: Long,
)

data class ExpensesPageResult(val items: List<ExpenseResult>, val total: Long, val page: Int, val size: Int)
data class MoneyTotalResult(val currency: String, val amount: BigDecimal)
data class RealizedMoneyResult(val currency: String, val expenses: BigDecimal, val clinical: BigDecimal, val total: BigDecimal)
data class CategoryCostResult(val category: String, val currency: String, val amount: BigDecimal)
data class MonthlyCostResult(val month: YearMonth, val currency: String, val amount: BigDecimal)
data class ForecastCostResult(
    val occurrenceId: UUID,
    val petId: Long,
    val title: String,
    val dueAt: Instant,
    val amount: BigDecimal,
    val currency: String,
)
data class FinancialInsightResult(val code: String, val message: String)

data class FinanceOverviewResult(
    val from: LocalDate,
    val to: LocalDate,
    val forecastTo: LocalDate,
    val realized: List<RealizedMoneyResult>,
    val forecast: List<MoneyTotalResult>,
    val byCategory: List<CategoryCostResult>,
    val monthly: List<MonthlyCostResult>,
    val upcoming: List<ForecastCostResult>,
    val insights: List<FinancialInsightResult>,
)
