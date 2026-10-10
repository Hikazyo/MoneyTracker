package com.moneytracker.app.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.db.dao.TransactionWithCategory
import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.model.MonthKey
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.model.toLocalDate
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.data.repository.MonthLedger
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** 列表里的一行账目（已格式化为可直接渲染的文案）。 */
data class EntryRow(
  val id: Long,
  val categoryName: String,
  val iconKey: String,
  val note: String?,
  val timeLabel: String,
  val signedAmount: String,
  val isExpense: Boolean,
)

/** 按天分组，带当日小计。 */
data class DayGroup(val dayLabel: String, val summary: String, val entries: List<EntryRow>)

data class LedgerUiState(
  val isLoading: Boolean = true,
  val monthLabel: String = "",
  val expenseText: String = "0.00",
  val incomeText: String = "0.00",
  val balanceText: String = "0.00",
  val dayGroups: List<DayGroup> = emptyList(),
  val isCurrentMonth: Boolean = true,
  val isEmpty: Boolean = false,
  val errorMessage: String? = null,
)

class LedgerViewModel(
  private val repository: LedgerRepository,
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val today: () -> LocalDate = { LocalDate.now(zone) },
) : ViewModel() {

  // 初始月份由注入的 today() 推导，而不是另调 MonthKey.current()：
  // 让「当前月份」与「今天/昨天」来自同一个时间源，测试里也能完全确定。
  private val selectedMonth = MutableStateFlow(MonthKey.of(YearMonth.from(today())))

  @OptIn(ExperimentalCoroutinesApi::class)
  val uiState: StateFlow<LedgerUiState> =
    selectedMonth
      .flatMapLatest { month -> repository.observeMonth(month).map { it.toUiState(month) } }
      .catch { emit(LedgerUiState(isLoading = false, errorMessage = it.message ?: "加载失败")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

  fun showPreviousMonth() {
    selectedMonth.update { it.plusMonths(-1) }
  }

  fun showNextMonth() {
    selectedMonth.update { it.plusMonths(1) }
  }

  /** 回到本月。跨了很多个月后，「回到本月」比连按十几次上个月实用得多。 */
  fun showCurrentMonth() {
    selectedMonth.value = MonthKey.of(YearMonth.from(today()))
  }

  private fun MonthLedger.toUiState(month: MonthKey): LedgerUiState {
    val now = today()
    return LedgerUiState(
      isLoading = false,
      monthLabel = LedgerFormatter.monthLabel(month),
      expenseText = Money.format(expenseMinor),
      incomeText = Money.format(incomeMinor),
      balanceText = LedgerFormatter.balance(balanceMinor),
      dayGroups = groupByDay(entries, now),
      isCurrentMonth = month == MonthKey.of(YearMonth.from(now)),
      isEmpty = entries.isEmpty(),
    )
  }

  private fun groupByDay(
    entries: List<TransactionWithCategory>,
    today: LocalDate,
  ): List<DayGroup> =
    entries
      .groupBy { it.transaction.occurredAt.toLocalDate(zone) }
      .entries
      // 账目本身已按时间倒序，这里按日期倒序即可保持整体顺序
      .sortedByDescending { it.key }
      .map { (date, items) ->
        val expenseMinor =
          items
            .filter { it.transaction.type == TransactionType.EXPENSE }
            .sumOf { it.transaction.amountMinor }
        val incomeMinor =
          items
            .filter { it.transaction.type == TransactionType.INCOME }
            .sumOf { it.transaction.amountMinor }

        DayGroup(
          dayLabel = LedgerFormatter.dayLabel(date, today),
          summary = LedgerFormatter.daySummary(expenseMinor, incomeMinor),
          entries =
            items.map { item ->
              val tx = item.transaction
              val isExpense = tx.type == TransactionType.EXPENSE
              EntryRow(
                id = tx.id,
                categoryName = item.category.name,
                iconKey = item.category.iconKey,
                note = tx.note,
                timeLabel = LedgerFormatter.timeLabel(tx.occurredAt, zone),
                signedAmount = LedgerFormatter.signedAmount(tx.amountMinor, isExpense),
                isExpense = isExpense,
              )
            },
        )
      }
}
