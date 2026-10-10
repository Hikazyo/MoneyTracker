package com.moneytracker.app.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.model.MonthKey
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.repository.BudgetSaveResult
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.data.repository.MonthBudget
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 分类预算的一行。 */
data class CategoryBudgetRow(
  val categoryId: Long,
  val name: String,
  val iconKey: String,
  val budgetMinor: Long?,
  val budgetText: String,
  val spentMinor: Long,
  val spentText: String,
  val ratio: Float?,
  val isOver: Boolean,
  val remainingLabel: String?,
)

/** 等待用户确认「分类预算之和超过总预算」的待保存操作（决策 2）。 */
data class PendingOverBudgetSave(
  val categoryId: Long,
  val requestedAmountMinor: Long,
  val totalMinor: Long,
  val requestedSumMinor: Long,
)

data class BudgetUiState(
  val isLoading: Boolean = true,
  val monthLabel: String = "",
  val isCurrentMonth: Boolean = true,
  val totalBudgetMinor: Long? = null,
  val totalBudgetText: String = "未设置",
  val totalSpentText: String = "0.00",
  val totalRatio: Float? = null,
  val totalRemainingLabel: String? = null,
  val totalIsOver: Boolean = false,
  val categoryRows: List<CategoryBudgetRow> = emptyList(),
  val categoryBudgetSumMinor: Long = 0L,
  val categoryBudgetSumText: String = "0.00",
  /** 分类预算之和已超过总预算（持久状态，界面常驻提示）。 */
  val categorySumExceedsTotal: Boolean = false,
  val pending: PendingOverBudgetSave? = null,
  val message: String? = null,
  val errorMessage: String? = null,
)

class BudgetViewModel(
  private val repository: LedgerRepository,
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val today: () -> LocalDate = { LocalDate.now(zone) },
) : ViewModel() {

  // 与其它页面一样，初始月份由注入的 today() 推导，保证时间源唯一
  private val selectedMonth = MutableStateFlow(MonthKey.of(YearMonth.from(today())))
  private val message = MutableStateFlow<String?>(null)
  private val pending = MutableStateFlow<PendingOverBudgetSave?>(null)

  @OptIn(ExperimentalCoroutinesApi::class)
  val uiState: StateFlow<BudgetUiState> =
    selectedMonth
      .flatMapLatest { month ->
        combine(
          repository.observeBudget(month),
          repository.observeCategories(TransactionType.EXPENSE),
          message,
          pending,
        ) { budget, categories, msg, pend -> buildState(month, budget, categories, msg, pend) }
      }
      .catch { emit(BudgetUiState(isLoading = false, errorMessage = it.message ?: "加载失败")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

  fun showPreviousMonth() {
    selectedMonth.update { it.plusMonths(-1) }
  }

  fun showNextMonth() {
    selectedMonth.update { it.plusMonths(1) }
  }

  fun showCurrentMonth() {
    selectedMonth.value = MonthKey.of(YearMonth.from(today()))
  }

  /** 传 null 表示清除该月总预算。 */
  fun saveTotalBudget(amountMinor: Long?) {
    viewModelScope.launch {
      when (repository.saveMonthlyBudget(selectedMonth.value, amountMinor)) {
        is BudgetSaveResult.Saved ->
          message.value = if (amountMinor == null) "已清除总预算" else "总预算已保存"
        is BudgetSaveResult.InvalidAmount -> message.value = "请输入大于 0 的金额"
        is BudgetSaveResult.ExceedsTotal -> Unit // 总预算本身不涉及越界，不会走到这里
      }
    }
  }

  /**
   * 保存分类预算（决策 2）。
   *
   * 若「该月分类预算之和」将超过总预算，Repository 会返回 [BudgetSaveResult.ExceedsTotal]
   * 且**不写入**；这里把它转成待确认状态，由界面提示用户是否仍然保存。
   */
  fun saveCategoryBudget(categoryId: Long, amountMinor: Long?) {
    viewModelScope.launch {
      when (val result = repository.saveCategoryBudget(selectedMonth.value, categoryId, amountMinor)) {
        is BudgetSaveResult.Saved ->
          message.value = if (amountMinor == null) "已清除该分类预算" else "分类预算已保存"
        is BudgetSaveResult.InvalidAmount -> message.value = "请输入大于 0 的金额"
        is BudgetSaveResult.ExceedsTotal ->
          pending.value =
            PendingOverBudgetSave(
              categoryId = categoryId,
              requestedAmountMinor = amountMinor ?: 0L,
              totalMinor = result.totalMinor,
              requestedSumMinor = result.requestedSumMinor,
            )
      }
    }
  }

  /** 用户确认「仍然保存」。 */
  fun confirmOverBudgetSave() {
    val current = pending.value ?: return
    viewModelScope.launch {
      repository.saveCategoryBudget(
        month = selectedMonth.value,
        categoryId = current.categoryId,
        amountMinor = current.requestedAmountMinor,
        allowOverBudget = true,
      )
      pending.value = null
      message.value = "已保存，分类预算之和已超过总预算"
    }
  }

  fun dismissOverBudgetSave() {
    pending.value = null
  }

  fun onMessageShown() {
    message.value = null
  }

  private fun buildState(
    month: MonthKey,
    budget: MonthBudget,
    categories: List<CategoryEntity>,
    message: String?,
    pending: PendingOverBudgetSave?,
  ): BudgetUiState {
    val total = budget.totalMinor
    val totalSpent = budget.spentByCategory.values.sum()

    val rows =
      categories.map { category ->
        val limit = budget.categoryBudgets[category.id]
        val spent = budget.spentByCategory[category.id] ?: 0L
        CategoryBudgetRow(
          categoryId = category.id,
          name = category.name,
          iconKey = category.iconKey,
          budgetMinor = limit,
          budgetText = limit?.let { Money.formatGrouped(it) } ?: "未设置",
          spentMinor = spent,
          spentText = Money.formatGrouped(spent),
          ratio = BudgetMath.ratio(spent, limit),
          isOver = BudgetMath.isOverBudget(spent, limit),
          remainingLabel = limit?.let { BudgetMath.remainingLabel(spent, it) },
        )
      }

    val categorySum = budget.categoryBudgetSumMinor

    return BudgetUiState(
      isLoading = false,
      monthLabel = month.displayName(),
      isCurrentMonth = month == MonthKey.of(YearMonth.from(today())),
      totalBudgetMinor = total,
      totalBudgetText = total?.let { Money.formatGrouped(it) } ?: "未设置",
      totalSpentText = Money.formatGrouped(totalSpent),
      totalRatio = BudgetMath.ratio(totalSpent, total),
      totalRemainingLabel = total?.let { BudgetMath.remainingLabel(totalSpent, it) },
      totalIsOver = BudgetMath.isOverBudget(totalSpent, total),
      categoryRows = rows,
      categoryBudgetSumMinor = categorySum,
      categoryBudgetSumText = Money.formatGrouped(categorySum),
      // 决策 2 的常驻提示：只要分类预算之和超了总预算就一直显示
      categorySumExceedsTotal = total != null && categorySum > total,
      pending = pending,
      message = message,
    )
  }
}
