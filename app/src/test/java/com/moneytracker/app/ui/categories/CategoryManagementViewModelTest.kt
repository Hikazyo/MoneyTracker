package com.moneytracker.app.ui.categories

import com.moneytracker.app.data.backup.BackupCodec
import com.moneytracker.app.data.backup.BackupFile
import com.moneytracker.app.data.db.dao.CategoryTotal
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.db.entity.TransactionEntity
import com.moneytracker.app.data.export.ExportEntry
import com.moneytracker.app.data.importer.ImportedEntry
import com.moneytracker.app.data.model.MonthKey
import com.moneytracker.app.data.model.MonthTotal
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.repository.BudgetSaveResult
import com.moneytracker.app.data.repository.ImportResult
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.data.repository.MonthBudget
import com.moneytracker.app.data.repository.MonthLedger
import com.moneytracker.app.data.repository.RestoreResult
import com.moneytracker.app.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManagementViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  /**
   * 构造 ViewModel 并保持一个订阅者。
   *
   * 与其它页面的理由相同：`uiState` 用 `WhileSubscribed`，没有订阅者时上游不会跑，
   * 而 addCategory 的重名校验要读 `uiState.value`，状态没建好校验就会失真。
   */
  private fun TestScope.viewModel(
    repository: FakeCategoryRepository = FakeCategoryRepository()
  ): CategoryManagementViewModel {
    val viewModel = CategoryManagementViewModel(repository)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    return viewModel
  }

  // ---------- 分组与展示 ----------

  @Test
  fun categories_are_grouped_by_direction() = runTest(mainDispatcherRule.dispatcher) {
    val state = viewModel().uiState.value

    // expense / income 是完整列表（含已归档），在用与已归档由 active*/archived* 视图区分
    assertEquals(listOf("餐饮", "交通", "宠物", "健身", "咖啡"), state.expense.map { it.name })
    assertEquals(listOf("工资"), state.income.map { it.name })
  }

  @Test
  fun archived_categories_are_separated_from_active_ones() =
    runTest(mainDispatcherRule.dispatcher) {
      val state = viewModel().uiState.value

      assertEquals(listOf("餐饮", "交通", "宠物", "咖啡"), state.activeExpense.map { it.name })
      assertEquals(listOf("健身"), state.archivedExpense.map { it.name })
      assertTrue("已归档不应出现在在用列表里", state.activeExpense.none { it.name == "健身" })
    }

  @Test
  fun entry_counts_are_attached_to_each_row() = runTest(mainDispatcherRule.dispatcher) {
    val state = viewModel().uiState.value

    assertEquals(12, state.expense.first { it.name == "餐饮" }.entryCount)
    assertEquals(0, state.expense.first { it.name == "交通" }.entryCount)
  }

  @Test
  fun built_in_categories_are_marked_and_cannot_be_archived() =
    runTest(mainDispatcherRule.dispatcher) {
      val state = viewModel().uiState.value

      val builtIn = state.expense.first { it.name == "餐饮" }
      assertTrue(builtIn.isBuiltIn)
      assertEquals("内置分类不可归档", false, builtIn.canArchive)

      // 宠物名下有 3 笔账目 → 只能归档，不能删
      val custom = state.expense.first { it.name == "宠物" }
      assertEquals(false, custom.isBuiltIn)
      assertTrue(custom.canArchive)
      assertEquals(false, custom.canDelete)
    }

  @Test
  fun an_empty_custom_category_offers_delete_instead_of_archive() =
    runTest(mainDispatcherRule.dispatcher) {
      // 空的自定义分类归档没有意义（没有历史可留），两个按钮并列只会让人犹豫
      val state = viewModel().uiState.value

      val emptyCustom = state.expense.first { it.name == "咖啡" }
      assertEquals(false, emptyCustom.isBuiltIn)
      assertTrue("空的自定义分类应当可以删除", emptyCustom.canDelete)
      assertEquals("此时不该再并列一个归档按钮", false, emptyCustom.canArchive)
    }

  // ---------- 新建 ----------

  @Test
  fun adding_a_category_passes_trimmed_name_icon_and_type() =
    runTest(mainDispatcherRule.dispatcher) {
      val repository = FakeCategoryRepository()
      val viewModel = viewModel(repository)

      // 刻意用一个测试数据里没有的名字，否则会被重名校验挡住
      viewModel.addCategory(name = "  奶茶  ", iconKey = "coffee", type = TransactionType.EXPENSE)

      assertEquals(Triple("奶茶", "coffee", TransactionType.EXPENSE), repository.lastAdded)
      assertEquals("已添加「奶茶」", viewModel.uiState.value.message)
    }

  @Test
  fun blank_name_is_rejected() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeCategoryRepository()
    val viewModel = viewModel(repository)

    viewModel.addCategory(name = "   ", iconKey = "pet", type = TransactionType.EXPENSE)

    assertNull("不应调用仓储", repository.lastAdded)
    assertEquals("分类名不能为空", viewModel.uiState.value.message)
  }

  @Test
  fun duplicate_name_in_the_same_direction_is_rejected() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeCategoryRepository()
    val viewModel = viewModel(repository)

    viewModel.addCategory(name = "餐饮", iconKey = "food", type = TransactionType.EXPENSE)

    assertNull(repository.lastAdded)
    assertTrue(viewModel.uiState.value.message!!.contains("已存在同名分类"))
  }

  @Test
  fun same_name_in_the_other_direction_is_allowed() = runTest(mainDispatcherRule.dispatcher) {
    // 「餐饮」是支出分类，收入侧再建一个同名分类是合理的
    val repository = FakeCategoryRepository()
    val viewModel = viewModel(repository)

    viewModel.addCategory(name = "餐饮", iconKey = "food", type = TransactionType.INCOME)

    assertEquals(Triple("餐饮", "food", TransactionType.INCOME), repository.lastAdded)
  }

  @Test
  fun name_conflicting_with_an_archived_category_is_allowed() =
    runTest(mainDispatcherRule.dispatcher) {
      // 已归档的分类不占名字，否则用户会莫名其妙地被挡住
      val repository = FakeCategoryRepository()
      val viewModel = viewModel(repository)

      viewModel.addCategory(name = "健身", iconKey = "sport", type = TransactionType.EXPENSE)

      assertEquals(Triple("健身", "sport", TransactionType.EXPENSE), repository.lastAdded)
    }

  // ---------- 编辑 ----------

  @Test
  fun editing_passes_the_trimmed_name_and_the_new_icon() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeCategoryRepository()
    val viewModel = viewModel(repository)

    viewModel.editCategory(
      id = 20L,
      name = "  猫主子  ",
      iconKey = "heart",
      type = TransactionType.EXPENSE,
    )

    assertEquals(Triple(20L, "猫主子", "heart"), repository.lastUpdated)
    assertEquals("已保存「猫主子」", viewModel.uiState.value.message)
  }

  @Test
  fun editing_to_a_blank_name_is_rejected() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeCategoryRepository()
    val viewModel = viewModel(repository)

    viewModel.editCategory(20L, "   ", "pet", TransactionType.EXPENSE)

    assertNull(repository.lastUpdated)
    assertEquals("分类名不能为空", viewModel.uiState.value.message)
  }

  @Test
  fun keeping_the_same_name_while_changing_the_icon_is_not_a_duplicate() =
    runTest(mainDispatcherRule.dispatcher) {
      // 这条最容易写错：重名校验如果不把自己排除掉，「只换图标不改名」会被自己挡住
      val repository = FakeCategoryRepository()
      val viewModel = viewModel(repository)

      viewModel.editCategory(20L, "宠物", "heart", TransactionType.EXPENSE)

      assertEquals(Triple(20L, "宠物", "heart"), repository.lastUpdated)
    }

  @Test
  fun renaming_to_another_existing_name_is_rejected() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeCategoryRepository()
    val viewModel = viewModel(repository)

    viewModel.editCategory(20L, "餐饮", "pet", TransactionType.EXPENSE)

    assertNull(repository.lastUpdated)
    assertTrue(viewModel.uiState.value.message!!.contains("已存在同名分类"))
  }

  @Test
  fun changing_direction_reports_that_the_category_moved() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeCategoryRepository()
    val viewModel = viewModel(repository)

    viewModel.editCategory(20L, "宠物", "pet", TransactionType.INCOME)

    assertEquals(20L to TransactionType.INCOME, repository.lastTypeChange)
    assertEquals("已保存，并移到收入分类", viewModel.uiState.value.message)
  }

  @Test
  fun a_rejected_direction_change_is_not_reported_as_success() =
    runTest(mainDispatcherRule.dispatcher) {
      // 仓库层因为「名下有账目」拒绝改方向时，提示不能说「已移到收入分类」——
      // 那会让用户以为改成功了，而实际上分类还在支出侧
      val repository = FakeCategoryRepository().apply { typeChangeResult = false }
      val viewModel = viewModel(repository)

      viewModel.editCategory(20L, "宠物", "pet", TransactionType.INCOME)

      assertEquals("已保存「宠物」", viewModel.uiState.value.message)
    }

  // ---------- 删除 ----------

  @Test
  fun deleting_reports_the_category_name() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeCategoryRepository()
    val viewModel = viewModel(repository)
    val row = viewModel.uiState.value.expense.first { it.name == "宠物" }

    viewModel.deleteCategory(row)

    assertEquals(20L, repository.lastDeleted)
    assertEquals("已删除「宠物」", viewModel.uiState.value.message)
  }

  @Test
  fun a_refused_delete_says_what_to_do_instead() = runTest(mainDispatcherRule.dispatcher) {
    // 「删不掉」对用户是无信息量的，必须告诉他改用归档
    val repository = FakeCategoryRepository().apply { deleteResult = false }
    val viewModel = viewModel(repository)
    val row = viewModel.uiState.value.expense.first { it.name == "宠物" }

    viewModel.deleteCategory(row)

    val message = viewModel.uiState.value.message!!
    assertTrue("要说清为什么删不掉", message.contains("内置分类或名下还有账目"))
    assertTrue("要给出替代做法", message.contains("归档"))
  }

  // ---------- 归档与恢复 ----------

  @Test
  fun archiving_reports_that_history_is_kept() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeCategoryRepository()
    val viewModel = viewModel(repository)

    viewModel.archiveCategory(id = 20L)

    assertEquals(20L, repository.lastArchived)
    assertTrue(viewModel.uiState.value.message!!.contains("历史账目仍会保留"))
  }

  @Test
  fun unarchiving_restores_the_category() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeCategoryRepository()
    val viewModel = viewModel(repository)

    viewModel.unarchiveCategory(id = 21L)

    assertEquals(21L, repository.lastUnarchived)
    assertEquals("已恢复该分类", viewModel.uiState.value.message)
  }

  @Test
  fun message_can_be_cleared_after_being_shown() = runTest(mainDispatcherRule.dispatcher) {
    val viewModel = viewModel()
    viewModel.archiveCategory(20L)
    assertTrue(viewModel.uiState.value.message != null)

    viewModel.onMessageShown()

    assertNull(viewModel.uiState.value.message)
  }
}

private val DEFAULT_CATEGORIES =
  listOf(
    CategoryEntity(1, "餐饮", "food", TransactionType.EXPENSE, 0, isBuiltIn = true),
    CategoryEntity(2, "交通", "transport", TransactionType.EXPENSE, 1, isBuiltIn = true),
    CategoryEntity(20, "宠物", "pet", TransactionType.EXPENSE, 2, isBuiltIn = false),
    CategoryEntity(21, "健身", "sport", TransactionType.EXPENSE, 3, isBuiltIn = false, isArchived = true),
    CategoryEntity(22, "咖啡", "coffee", TransactionType.EXPENSE, 4, isBuiltIn = false),
    CategoryEntity(11, "工资", "salary", TransactionType.INCOME, 0, isBuiltIn = true),
  )

private class FakeCategoryRepository(
  initial: List<CategoryEntity> = DEFAULT_CATEGORIES,
  private val counts: Map<Long, Int> = mapOf(1L to 12, 20L to 3),
) : LedgerRepository {

  private val categories = MutableStateFlow(initial)

  var lastAdded: Triple<String, String, TransactionType>? = null
    private set
  var lastUpdated: Triple<Long, String, String>? = null
    private set
  var lastTypeChange: Pair<Long, TransactionType>? = null
    private set
  var lastDeleted: Long? = null
    private set
  var lastArchived: Long? = null
    private set
  var lastUnarchived: Long? = null
    private set

  /** 让测试能模拟「仓库层拒绝了改方向 / 删除」。 */
  var typeChangeResult: Boolean = true
  var deleteResult: Boolean = true

  override fun observeAllCategories(): Flow<List<CategoryEntity>> = categories

  override fun observeCategoryCounts(): Flow<Map<Long, Int>> = flowOf(counts)

  override suspend fun addCategory(name: String, iconKey: String, type: TransactionType): Long {
    lastAdded = Triple(name, iconKey, type)
    return 100L
  }

  override suspend fun updateCategory(id: Long, name: String, iconKey: String) {
    lastUpdated = Triple(id, name, iconKey)
  }

  /**
   * 模拟真实仓库的行为：**方向没变时不返回 true**。
   *
   * 若这里无条件返回 [typeChangeResult]，界面就会对「只是改了名字」也报「已移到收入分类」，
   * 而真实实现不会——测试替身过于宽松会让界面文案的 bug 溜过去。
   */
  override suspend fun changeCategoryType(id: Long, type: TransactionType): Boolean {
    lastTypeChange = id to type
    val current = categories.value.firstOrNull { it.id == id }?.type
    return typeChangeResult && current != null && current != type
  }

  override suspend fun deleteCategory(id: Long): Boolean {
    lastDeleted = id
    return deleteResult
  }

  override suspend fun archiveCategory(id: Long) {
    lastArchived = id
  }

  override suspend fun unarchiveCategory(id: Long) {
    lastUnarchived = id
  }

  override fun observeCategories(type: TransactionType?): Flow<List<CategoryEntity>> =
    flowOf(categories.value.filter { type == null || it.type == type })

  override fun observeMonth(month: MonthKey): Flow<MonthLedger> =
    flowOf(MonthLedger(month, emptyList(), 0L, 0L))

  override fun observeBudget(month: MonthKey): Flow<MonthBudget> =
    flowOf(MonthBudget(month, null, emptyMap(), emptyMap()))

  override fun observeCategoryTotals(
    month: MonthKey,
    type: TransactionType,
  ): Flow<List<CategoryTotal>> = flowOf(emptyList())

  override fun observeMonthlyTrend(
    endMonth: MonthKey,
    months: Int,
  ): Flow<List<MonthTotal>> = flowOf(emptyList())

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

  override suspend fun exportEntries(): List<ExportEntry> = emptyList()

  override suspend fun createBackup(exportedAt: Long): BackupFile =
    BackupFile(BackupCodec.CURRENT_SCHEMA_VERSION, exportedAt, emptyList(), emptyList())

  override suspend fun restoreBackup(backup: BackupFile): RestoreResult = RestoreResult.Success(0)

  override suspend fun importEntries(
    entries: List<ImportedEntry>,
    categoryIdByIconKey: Map<String, Long>,
    fallbackCategoryId: Long,
  ): ImportResult = ImportResult(entries.size, 0)
}
