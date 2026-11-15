package xyz.ikuyo.finance.feature.home

import xyz.ikuyo.finance.domain.model.BalanceSummary
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillSortOrder
import xyz.ikuyo.finance.domain.model.BudgetStatus
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.HomeStats
import xyz.ikuyo.finance.domain.model.PaymentMethod

data class HomeUiState(
    val isLoading: Boolean = true,
    val monthLabel: String = "",
    val sortOrder: BillSortOrder = BillSortOrder.OCCURRED_DESC,
    val balance: BalanceSummary = BalanceSummary(),
    val stats: HomeStats = HomeStats(),
    val budget: BudgetStatus = BudgetStatus(),
    val recentBills: List<Bill> = emptyList(),
    val categories: List<Category> = emptyList(),
    val lastPaymentMethod: PaymentMethod? = null,
) {
    val hasBills: Boolean get() = recentBills.isNotEmpty()

    val budgetSubtitle: String
        get() = if (budget.amountMinor == null) "尚未设置本月预算" else "剩余 ${xyz.ikuyo.finance.core.Money.format(budget.remainingMinor)}"
}