package com.moneytracker.app.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.db.dao.CategoryTotal
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.model.MonthKey
import com.moneytracker.app.data.model.MonthTotal
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.repository.LedgerRepository
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

/** 占比图里的一个扇区，同时也是排行榜的一行。 */
data class CategorySlice(
  val categoryId: Long,
  val name: String,
  val iconKey: String,
  val amountText: String,
  val percentText: String,
  val ratio: Float,
)

/** 趋势图里的一根柱子（一个月）。 */
data class MonthBar(
  val label: String,
  val expenseMinor: Long,
  val incomeMinor: Long,
  val isSelected: Boolean,
)

data class StatisticsUiState(
  val isLoading: Boolean = true,
  val monthLabel: String = "",
  val isCurrentMonth: Boolean = true,
  val isExpense: Boolean = true,
  val totalText: String = "0.00",
  val slices: List<CategorySlice> = emptyList(),
  val trend: List<MonthBar> = emptyList(),
  val isEmpty: Boolean = true,
  val errorMessage: String? = null,
)

class StatisticsViewModel(
  private val repository: LedgerRepository,
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val today: () -> LocalDate = { LocalDate.now(zone) },
  private val trendMonths: Int = 6,
) : ViewModel() {

  // 初始月份由注入的 today() 推导，保证与 isCurrentMonth 使用同一个时间源
  private val selectedMonth = MutableStateFlow(MonthKey.of(YearMonth.from(today())))
  private val type = MutableStateFlow(TransactionType.EXPENSE)

  @OptIn(ExperimentalCoroutinesApi::class)
  val uiState: StateFlow<StatisticsUiState> =
    combine(selectedMonth, type) { month, selected -> month to selected }
      .flatMapLatest { (month, selected) ->
        combine(
          repository.observeCategoryTotals(month, selected),
          repository.observeAllCategories(),
          // 趋势以「当前选中的月份」为终点：切换月份时趋势一起平移，便于查看历史
          repository.observeMonthlyTrend(month, trendMonths),
        ) { totals, categories, trend -> buildState(month, selected, totals, categories, trend) }
      }
      .catch { emit(StatisticsUiState(isLoading = false, errorMessage = it.message ?: "加载失败")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsUiState())

  fun showPreviousMonth() {
    selectedMonth.update { it.plusMonths(-1) }
  }

  fun showNextMonth() {
    selectedMonth.update { it.plusMonths(1) }
  }

  fun showCurrentMonth() {
    selectedMonth.value = MonthKey.of(YearMonth.from(today()))
  }

  fun setType(isExpense: Boolean) {
    type.value = if (isExpense) TransactionType.EXPENSE else TransactionType.INCOME
  }

  private fun buildState(
    month: MonthKey,
    selected: TransactionType,
    totals: List<CategoryTotal>,
    categories: List<CategoryEntity>,
    trend: List<MonthTotal>,
  ): StatisticsUiState {
    val byId = categories.associateBy { it.id }
    val total = totals.sumOf { it.totalMinor }
    val ratios = StatisticsMath.ratios(totals.map { it.totalMinor })

    val slices =
      totals.mapIndexed { index, item ->
        val category = byId[item.categoryId]
        CategorySlice(
          categoryId = item.categoryId,
          // 归档分类仍在 observeAllCategories 里，所以历史账目能正常显示名称
          name = category?.name ?: "未知分类",
          iconKey = category?.iconKey.orEmpty(),
          amountText = Money.formatGrouped(item.totalMinor),
          percentText = StatisticsMath.percentText(item.totalMinor, total),
          ratio = ratios[index],
        )
      }

    return StatisticsUiState(
      isLoading = false,
      monthLabel = month.displayName(),
      isCurrentMonth = month == MonthKey.of(YearMonth.from(today())),
      isExpense = selected == TransactionType.EXPENSE,
      totalText = Money.formatGrouped(total),
      slices = slices,
      trend =
        trend.map { item ->
          MonthBar(
            label = "${item.month.toYearMonth().monthValue}月",
            expenseMinor = item.expenseMinor,
            incomeMinor = item.incomeMinor,
            isSelected = item.month == month,
          )
        },
      isEmpty = totals.isEmpty(),
    )
  }
}
