package com.moneytracker.app.ui.budget

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

  /**
   * 构造 ViewModel，并**持续保持一个订阅者**。
   *
   * BudgetViewModel 的 uiState 用 `WhileSubscribed(5000)`。若测试里只靠 `first {}` 取一次值、
   * 之后又推进了虚拟时间（`advanceUntilIdle`），上游会被停掉；此后对 `pending` / `message`
   * 的更新就不会再反映到 `uiState.value` 上，断言会读到过期状态。
   * 真实界面是一直在收集的，测试里保持订阅才符合实际情形。
   */
  private fun TestScope.viewModel(repository: FakeBudgetRepository): BudgetViewModel {
    val vm = BudgetViewModel(repository, zone, today = { LocalDate.of(2026, 10, 10) })
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    return vm
  }

  // ---------- 展示 ----------

  @Test
  fun rows_cover_every_expense_category_including_ones_without_budget() =
    runTest(mainDispatcherRule.dispatcher) {
      val repository =
        FakeBudgetRepository(
          budget =
            MonthBudget(
              month = october,
              totalMinor = 100_000L,
              categoryBudgets = mapOf(1L to 50_000L),
              spentByCategory = mapOf(1L to 32_000L),
            )
        )
      val state = viewModel(repository).uiState.first { !it.isLoading }

      assertEquals(listOf("餐饮", "交通"), state.categoryRows.map { it.name })
      assertEquals("500.00", state.categoryRows[0].budgetText)
      assertEquals("未设置", state.categoryRows[1].budgetText)
      assertNull("未设预算的分类不应有进度比例", state.categoryRows[1].ratio)
    }

  @Test
  fun total_progress_and_remaining_are_computed() = runTest(mainDispatcherRule.dispatcher) {
    val repository =
      FakeBudgetRepository(
        budget =
          MonthBudget(
            month = october,
            totalMinor = 100_000L,
            categoryBudgets = emptyMap(),
            spentByCategory = mapOf(1L to 32_000L, 2L to 8_800L),
          )
      )
    val state = viewModel(repository).uiState.first { !it.isLoading }

    assertEquals("1,000.00", state.totalBudgetText)
    assertEquals("408.00", state.totalSpentText)
    assertEquals("剩余 592.00", state.totalRemainingLabel)
    assertFalse(state.totalIsOver)
    assertEquals(0.408f, state.totalRatio!!, 1e-4f)
  }

  @Test
  fun exceeding_the_total_budget_marks_over_and_flips_the_label() =
    runTest(mainDispatcherRule.dispatcher) {
      val repository =
        FakeBudgetRepository(
          budget =
            MonthBudget(
              month = october,
              totalMinor = 10_000L,
              categoryBudgets = emptyMap(),
              spentByCategory = mapOf(1L to 32_000L),
            )
        )
      val state = viewModel(repository).uiState.first { !it.isLoading }

      assertTrue(state.totalIsOver)
      assertEquals("超支 220.00", state.totalRemainingLabel)
      assertTrue("比例应能超过 1", state.totalRatio!! > 1f)
    }

  @Test
  fun category_row_marks_over_budget() = runTest(mainDispatcherRule.dispatcher) {
    val repository =
      FakeBudgetRepository(
        budget =
          MonthBudget(
            month = october,
            totalMinor = null,
            categoryBudgets = mapOf(1L to 10_000L, 2L to 20_000L),
            spentByCategory = mapOf(1L to 32_000L, 2L to 8_800L),
          )
      )
    val state = viewModel(repository).uiState.first { !it.isLoading }

    val food = state.categoryRows.first { it.name == "餐饮" }
    assertTrue(food.isOver)
    assertEquals("超支 220.00", food.remainingLabel)

    val transport = state.categoryRows.first { it.name == "交通" }
    assertFalse(transport.isOver)
    assertEquals("剩余 112.00", transport.remainingLabel)
  }

  @Test
  fun unset_total_budget_shows_placeholder() = runTest(mainDispatcherRule.dispatcher) {
    val repository =
      FakeBudgetRepository(
        budget = MonthBudget(october, null, emptyMap(), mapOf(1L to 1000L))
      )
    val state = viewModel(repository).uiState.first { !it.isLoading }

    assertEquals("未设置", state.totalBudgetText)
    assertNull(state.totalRatio)
    assertNull(state.totalRemainingLabel)
  }

  // ---------- 决策 2：分类预算之和超过总预算 ----------

  @Test
  fun existing_category_sum_exceeding_total_is_flagged_in_state() =
    runTest(mainDispatcherRule.dispatcher) {
      val repository =
        FakeBudgetRepository(
          budget =
            MonthBudget(
              month = october,
              totalMinor = 100_000L,
              categoryBudgets = mapOf(1L to 80_000L, 2L to 50_000L),
              spentByCategory = emptyMap(),
            )
        )
      val state = viewModel(repository).uiState.first { !it.isLoading }

      assertEquals(130_000L, state.categoryBudgetSumMinor)
      assertTrue("分类预算之和超总预算时应常驻提示", state.categorySumExceedsTotal)
    }

  @Test
  fun category_sum_equal_to_total_is_not_flagged() = runTest(mainDispatcherRule.dispatcher) {
    val repository =
      FakeBudgetRepository(
        budget =
          MonthBudget(
            month = october,
            totalMinor = 100_000L,
            categoryBudgets = mapOf(1L to 60_000L, 2L to 40_000L),
            spentByCategory = emptyMap(),
          )
      )
    val state = viewModel(repository).uiState.first { !it.isLoading }

    assertFalse("恰好等于不算超过", state.categorySumExceedsTotal)
  }

  @Test
  fun saving_a_category_that_would_exceed_total_asks_for_confirmation() =
    runTest(mainDispatcherRule.dispatcher) {
      val repository =
        FakeBudgetRepository(
          budget =
            MonthBudget(
              month = october,
              totalMinor = 100_000L,
              categoryBudgets = mapOf(1L to 80_000L),
              spentByCategory = emptyMap(),
            )
        )
      val viewModel = viewModel(repository)
      viewModel.uiState.first { !it.isLoading }

      viewModel.saveCategoryBudget(categoryId = 2L, amountMinor = 50_000L)
      advance()

      val pending = viewModel.uiState.value.pending
      assertNotNull("应转为待确认状态，而不是直接写入", pending)
      assertEquals(2L, pending!!.categoryId)
      assertEquals(100_000L, pending.totalMinor)
      assertEquals(130_000L, pending.requestedSumMinor)
      assertFalse("未确认前不允许带 allowOverBudget 写入", repository.lastAllowOverBudget == true)
    }

  @Test
  fun confirming_saves_with_allow_over_budget_flag() = runTest(mainDispatcherRule.dispatcher) {
    val repository =
      FakeBudgetRepository(
        budget =
          MonthBudget(october, 100_000L, mapOf(1L to 80_000L), emptyMap())
      )
    val viewModel = viewModel(repository)
    viewModel.uiState.first { !it.isLoading }

    viewModel.saveCategoryBudget(categoryId = 2L, amountMinor = 50_000L)
    advance()
    viewModel.confirmOverBudgetSave()
    advance()

    assertNull(viewModel.uiState.value.pending)
    assertEquals(true, repository.lastAllowOverBudget)
    assertEquals(50_000L, repository.lastSavedAmount)
    assertTrue(viewModel.uiState.value.message!!.contains("超过总预算"))
  }

  @Test
  fun dismissing_pending_discards_the_save() = runTest(mainDispatcherRule.dispatcher) {
    val repository =
      FakeBudgetRepository(
        budget =
          MonthBudget(october, 100_000L, mapOf(1L to 80_000L), emptyMap())
      )
    val viewModel = viewModel(repository)
    viewModel.uiState.first { !it.isLoading }

    viewModel.saveCategoryBudget(categoryId = 2L, amountMinor = 50_000L)
    advance()
    viewModel.dismissOverBudgetSave()
    advance()

    assertNull(viewModel.uiState.value.pending)
    assertNull("取消后不应写入", repository.lastSavedAmount)
  }

  // ---------- 保存总预算与清除 ----------

  @Test
  fun saving_total_budget_stores_the_value() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeBudgetRepository(budget = MonthBudget(october, null, emptyMap(), emptyMap()))
    val viewModel = viewModel(repository)
    viewModel.uiState.first { !it.isLoading }

    viewModel.saveTotalBudget(200_000L)
    advance()

    assertEquals(200_000L, repository.savedTotal)
    assertEquals("总预算已保存", viewModel.uiState.value.message)
  }

  @Test
  fun clearing_total_budget_passes_null_and_reports_it() = runTest(mainDispatcherRule.dispatcher) {
    val repository =
      FakeBudgetRepository(budget = MonthBudget(october, 100_000L, emptyMap(), emptyMap()))
    val viewModel = viewModel(repository)
    viewModel.uiState.first { !it.isLoading }

    viewModel.saveTotalBudget(null)
    advance()

    assertTrue(repository.totalCleared)
    assertEquals("已清除总预算", viewModel.uiState.value.message)
  }

  @Test
  fun invalid_amount_reports_message_and_writes_nothing() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeBudgetRepository(budget = MonthBudget(october, null, emptyMap(), emptyMap()))
    val viewModel = viewModel(repository)
    viewModel.uiState.first { !it.isLoading }

    viewModel.saveTotalBudget(0L)
    advance()

    assertEquals("请输入大于 0 的金额", viewModel.uiState.value.message)
    assertNull(repository.savedTotal)
  }

  @Test
  fun saving_a_category_without_exceeding_does_not_ask_for_confirmation() =
    runTest(mainDispatcherRule.dispatcher) {
      val repository =
        FakeBudgetRepository(
          budget = MonthBudget(october, 100_000L, mapOf(1L to 10_000L), emptyMap())
        )
      val viewModel = viewModel(repository)
      viewModel.uiState.first { !it.isLoading }

      viewModel.saveCategoryBudget(categoryId = 2L, amountMinor = 20_000L)
      advance()

      assertNull(viewModel.uiState.value.pending)
      assertEquals(20_000L, repository.lastSavedAmount)
      assertEquals("分类预算已保存", viewModel.uiState.value.message)
    }

  /** 让 viewModelScope 里刚启动的协程跑完。 */
  private fun advance() {
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
  }
}

private val october = MonthKey.of(YearMonth.of(2026, 10))

private val EXPENSE_CATEGORIES =
  listOf(
    CategoryEntity(id = 1, name = "餐饮", iconKey = "food", type = TransactionType.EXPENSE, sortOrder = 0),
    CategoryEntity(id = 2, name = "交通", iconKey = "transport", type = TransactionType.EXPENSE, sortOrder = 1),
  )

/**
 * 模拟真实的预算校验行为，让 ViewModel 测试聚焦在 ViewModel 自身。
 * 校验算法本身由 `BudgetValidationTest` 覆盖。
 */
private class FakeBudgetRepository(
  private val budget: MonthBudget,
  private val categories: List<CategoryEntity> = EXPENSE_CATEGORIES,
) : LedgerRepository {

  var savedTotal: Long? = null
    private set
  var totalCleared = false
    private set
  var lastSavedAmount: Long? = null
    private set
  var lastAllowOverBudget: Boolean? = null
    private set

  override fun observeBudget(month: MonthKey): Flow<MonthBudget> = flowOf(budget)

  override fun observeCategories(type: TransactionType?): Flow<List<CategoryEntity>> =
    flowOf(categories.filter { type == null || it.type == type })

  override fun observeAllCategories(): Flow<List<CategoryEntity>> = flowOf(categories)

  override fun observeMonth(month: MonthKey): Flow<MonthLedger> =
    flowOf(MonthLedger(month, emptyList(), 0L, 0L))

  override suspend fun saveMonthlyBudget(month: MonthKey, amountMinor: Long?): BudgetSaveResult {
    if (amountMinor == null) {
      totalCleared = true
      return BudgetSaveResult.Saved
    }
    if (amountMinor <= 0L) return BudgetSaveResult.InvalidAmount
    savedTotal = amountMinor
    return BudgetSaveResult.Saved
  }

  override suspend fun saveCategoryBudget(
    month: MonthKey,
    categoryId: Long,
    amountMinor: Long?,
    allowOverBudget: Boolean,
  ): BudgetSaveResult {
    lastAllowOverBudget = allowOverBudget
    if (amountMinor == null) return BudgetSaveResult.Saved
    if (amountMinor <= 0L) return BudgetSaveResult.InvalidAmount

    val others = budget.categoryBudgets.filterKeys { it != categoryId }.values.sum()
    val total = budget.totalMinor
    val newSum = others + amountMinor
    if (total != null && newSum > total && !allowOverBudget) {
      return BudgetSaveResult.ExceedsTotal(total, newSum)
    }
    lastSavedAmount = amountMinor
    return BudgetSaveResult.Saved
  }

  override fun observeCategoryTotals(
    month: MonthKey,
    type: TransactionType,
  ): Flow<List<CategoryTotal>> = flowOf(emptyList())

  override fun observeMonthlyTrend(
    endMonth: MonthKey,
    months: Int,
  ): Flow<List<MonthTotal>> = flowOf(emptyList())

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

  override suspend fun addCategory(name: String, iconKey: String, type: TransactionType): Long = 0L

  override suspend fun archiveCategory(id: Long) {}

  override suspend fun unarchiveCategory(id: Long) {}

  override suspend fun updateCategory(id: Long, name: String, iconKey: String) {}

  override suspend fun changeCategoryType(id: Long, type: TransactionType): Boolean = false

  override suspend fun deleteCategory(id: Long): Boolean = false

  override fun observeCategoryCounts(): Flow<Map<Long, Int>> = flowOf(emptyMap())
}
