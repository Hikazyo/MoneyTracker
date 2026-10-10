package com.moneytracker.app.ui.statistics

import com.moneytracker.app.data.backup.BackupCodec
import com.moneytracker.app.data.backup.BackupFile
import com.moneytracker.app.data.db.dao.CategoryTotal
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.db.entity.TransactionEntity
import com.moneytracker.app.data.export.ExportEntry
import com.moneytracker.app.data.model.MonthKey
import com.moneytracker.app.data.model.MonthTotal
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.repository.BudgetSaveResult
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.data.repository.MonthBudget
import com.moneytracker.app.data.repository.MonthLedger
import com.moneytracker.app.data.repository.RestoreResult
import com.moneytracker.app.testing.MainDispatcherRule
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

  private fun viewModel(
    expenseTotals: List<CategoryTotal> = emptyList(),
    incomeTotals: List<CategoryTotal> = emptyList(),
    trend: List<MonthTotal> = emptyList(),
  ) = StatisticsViewModel(
    repository = FakeStatisticsRepository(expenseTotals, incomeTotals, trend),
    zone = zone,
    today = { LocalDate.of(2026, 10, 10) },
    trendMonths = 6,
  )

  @Test
  fun slices_carry_category_details_amounts_and_percentages() =
    runTest(mainDispatcherRule.dispatcher) {
      val state =
        viewModel(
            // 注意单位是「分」：300_000 分 = ¥3,000.00
            expenseTotals =
              listOf(
                CategoryTotal(categoryId = 1, totalMinor = 300_000, entryCount = 3),
                CategoryTotal(categoryId = 2, totalMinor = 100_000, entryCount = 1),
              )
          )
          .uiState
          .first { !it.isLoading }

      assertEquals("4,000.00", state.totalText)
      assertEquals(2, state.slices.size)

      val first = state.slices[0]
      assertEquals("餐饮", first.name)
      assertEquals("food", first.iconKey)
      assertEquals("3,000.00", first.amountText)
      assertEquals("75.0%", first.percentText)
      assertEquals(0.75f, first.ratio, 1e-4f)

      assertEquals("25.0%", state.slices[1].percentText)
    }

  @Test
  fun selected_month_is_derived_from_injected_today() = runTest(mainDispatcherRule.dispatcher) {
    val state = viewModel(expenseTotals = listOf(CategoryTotal(1, 100, 1))).uiState.first { !it.isLoading }
    assertEquals("2026年10月", state.monthLabel)
    assertTrue("注入今天为 2026-10-10，所以当前月份就是本月", state.isCurrentMonth)
  }

  @Test
  fun empty_totals_mark_state_as_empty() = runTest(mainDispatcherRule.dispatcher) {
    val state = viewModel().uiState.first { !it.isLoading }
    assertTrue(state.isEmpty)
    assertEquals("0.00", state.totalText)
    assertTrue(state.slices.isEmpty())
  }

  @Test
  fun unknown_category_falls_back_to_placeholder_name() = runTest(mainDispatcherRule.dispatcher) {
    // 分类被物理删除理论上不会发生（外键 RESTRICT），但真出现时不能崩，也不能显示空白
    val state =
      viewModel(expenseTotals = listOf(CategoryTotal(categoryId = 999, totalMinor = 500, entryCount = 1)))
        .uiState
        .first { !it.isLoading }

    assertEquals("未知分类", state.slices.single().name)
  }

  @Test
  fun switching_to_income_reloads_with_income_totals() = runTest(mainDispatcherRule.dispatcher) {
    val viewModel =
      viewModel(
        expenseTotals = listOf(CategoryTotal(1, 300_000, 2)),
        incomeTotals = listOf(CategoryTotal(3, 800_000, 1)),
      )

    val expenseState = viewModel.uiState.first { !it.isLoading }
    assertTrue(expenseState.isExpense)
    assertEquals("餐饮", expenseState.slices.single().name)

    viewModel.setType(isExpense = false)
    val incomeState = viewModel.uiState.first { !it.isExpense }
    assertFalse(incomeState.isExpense)
    assertEquals("工资", incomeState.slices.single().name)
    assertEquals("8,000.00", incomeState.totalText)
  }

  @Test
  fun trend_maps_to_month_labels_and_marks_the_selected_month() =
    runTest(mainDispatcherRule.dispatcher) {
      val state =
        viewModel(
            trend =
              listOf(
                MonthTotal(MonthKey.of(YearMonth.of(2026, 9)), expenseMinor = 1000, incomeMinor = 2000),
                MonthTotal(MonthKey.of(YearMonth.of(2026, 10)), expenseMinor = 3000, incomeMinor = 4000),
              )
          )
          .uiState
          .first { !it.isLoading }

      assertEquals(listOf("9月", "10月"), state.trend.map { it.label })
      assertEquals(1000L, state.trend[0].expenseMinor)
      assertEquals(2000L, state.trend[0].incomeMinor)
      assertEquals(3000L, state.trend[1].expenseMinor)
      assertEquals(4000L, state.trend[1].incomeMinor)

      assertFalse("上月不应被高亮", state.trend[0].isSelected)
      assertTrue("选中月份应被高亮", state.trend[1].isSelected)
    }
}

private val DEFAULT_CATEGORIES =
  listOf(
    CategoryEntity(id = 1, name = "餐饮", iconKey = "food", type = TransactionType.EXPENSE, sortOrder = 0),
    CategoryEntity(id = 2, name = "交通", iconKey = "transport", type = TransactionType.EXPENSE, sortOrder = 1),
    CategoryEntity(id = 3, name = "工资", iconKey = "salary", type = TransactionType.INCOME, sortOrder = 0),
  )

private class FakeStatisticsRepository(
  private val expenseTotals: List<CategoryTotal>,
  private val incomeTotals: List<CategoryTotal>,
  private val trend: List<MonthTotal>,
) : LedgerRepository {

  override fun observeCategories(type: TransactionType?): Flow<List<CategoryEntity>> =
    flowOf(DEFAULT_CATEGORIES.filter { type == null || it.type == type })

  override fun observeAllCategories(): Flow<List<CategoryEntity>> = flowOf(DEFAULT_CATEGORIES)

  override fun observeMonth(month: MonthKey): Flow<MonthLedger> =
    flowOf(MonthLedger(month, emptyList(), 0L, 0L))

  override fun observeBudget(month: MonthKey): Flow<MonthBudget> =
    flowOf(MonthBudget(month, null, emptyMap(), emptyMap()))

  override fun observeCategoryTotals(
    month: MonthKey,
    type: TransactionType,
  ): Flow<List<CategoryTotal>> =
    flowOf(if (type == TransactionType.EXPENSE) expenseTotals else incomeTotals)

  override fun observeMonthlyTrend(endMonth: MonthKey, months: Int): Flow<List<MonthTotal>> =
    flowOf(trend)

  override suspend fun exportEntries(): List<ExportEntry> = emptyList()

  override suspend fun createBackup(exportedAt: Long): BackupFile =
    BackupFile(BackupCodec.CURRENT_SCHEMA_VERSION, exportedAt, emptyList(), emptyList())

  override suspend fun restoreBackup(backup: BackupFile): RestoreResult = RestoreResult.Success(0)

  override suspend fun importEntries(
    entries: List<com.moneytracker.app.data.importer.ImportedEntry>,
    categoryIdByIconKey: Map<String, Long>,
    fallbackCategoryId: Long,
  ): com.moneytracker.app.data.repository.ImportResult =
    com.moneytracker.app.data.repository.ImportResult(entries.size, 0)

  override suspend fun findTransaction(id: Long): TransactionEntity? = null

  override suspend fun addTransaction(
    amountMinor: Long,
    type: TransactionType,
    categoryId: Long,
    note: String?,
    occurredAt: Long,
  ): Long = 0L

  override suspend fun updateTransaction(
    id: Long,
    amountMinor: Long,
    type: TransactionType,
    categoryId: Long,
    note: String?,
    occurredAt: Long,
  ) {}

  override suspend fun deleteTransaction(id: Long) {}

  override suspend fun saveMonthlyBudget(
    month: MonthKey,
    amountMinor: Long?,
  ): BudgetSaveResult = BudgetSaveResult.Saved

  override suspend fun saveCategoryBudget(
    month: MonthKey,
    categoryId: Long,
    amountMinor: Long?,
    allowOverBudget: Boolean,
  ): BudgetSaveResult = BudgetSaveResult.Saved

  override suspend fun addCategory(name: String, iconKey: String, type: TransactionType): Long = 0L

  override suspend fun archiveCategory(id: Long) {}

  override suspend fun unarchiveCategory(id: Long) {}

  override suspend fun updateCategory(id: Long, name: String, iconKey: String) {}

  override suspend fun changeCategoryType(id: Long, type: TransactionType): Boolean = false

  override suspend fun deleteCategory(id: Long): Boolean = false

  override fun observeCategoryCounts(): Flow<Map<Long, Int>> = flowOf(emptyMap())
}
