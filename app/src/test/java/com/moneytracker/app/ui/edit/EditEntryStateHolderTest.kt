package com.moneytracker.app.ui.edit

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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑页状态机测试。
 *
 * 状态持有者用 `backgroundScope` 承载它内部的协程（那个对分类表的收集永不结束，
 * 放普通测试作用域里会让 runTest 一直等下去）；`advanceUntilIdle` 用来把
 * init 里的首次数据发射推进完成。
 */
class EditEntryStateHolderTest {

  private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

  /**
   * 每个用例都用 UnconfinedTestDispatcher 建一个独立的 runTest。
   *
   * 状态持有者在 init 里就启动了对分类表的收集；用默认的 StandardTestDispatcher 时
   * 它不会在断言前跑起来，分类列表会一直是空的。
   */
  private fun stateHolderTest(body: suspend TestScope.() -> Unit) =
    runTest(UnconfinedTestDispatcher()) { body() }

  private fun TestScope.newHolder(
    repository: FakeLedgerRepository = FakeLedgerRepository(),
    entryId: Long? = null,
    nowMillis: Long = FIXED_NOW,
  ) = EditEntryStateHolder(repository, entryId, backgroundScope, zone, clock = { nowMillis })

  // ---------- 初始状态 ----------

  @Test
  fun new_entry_starts_empty_and_cannot_save() = stateHolderTest {
    val holder = newHolder()
    advanceUntilIdle()

    val state = holder.state.value
    assertFalse(state.isEditing)
    assertEquals("0.00", state.amountDisplay)
    assertFalse("未输入金额时不能保存", state.canSave)
  }

  @Test
  fun only_categories_matching_current_type_are_offered() = stateHolderTest {
    val holder = newHolder()
    advanceUntilIdle()

    val state = holder.state.value
    assertTrue(state.isExpense)
    assertEquals(listOf("餐饮", "交通"), state.categories.map { it.name })
  }

  @Test
  fun first_category_of_current_type_is_preselected() = stateHolderTest {
    val holder = newHolder()
    advanceUntilIdle()

    assertEquals("餐饮", holder.state.value.categories.first { it.isSelected }.name)
  }

  // ---------- 金额输入 ----------

  @Test
  fun entering_amount_updates_display_and_enables_save() = stateHolderTest {
    val holder = newHolder()
    advanceUntilIdle()

    holder.onDigit('3')
    holder.onDigit('2')

    assertEquals("32.00", holder.state.value.amountDisplay)
    assertTrue(holder.state.value.canSave)
  }

  @Test
  fun zero_amount_cannot_be_saved() = stateHolderTest {
    val holder = newHolder()
    advanceUntilIdle()

    holder.onDigit('0')
    assertFalse("金额为 0 不应可保存", holder.state.value.canSave)
  }

  @Test
  fun backspace_and_clear_modify_amount() = stateHolderTest {
    val holder = newHolder()
    advanceUntilIdle()

    holder.onDigit('1')
    holder.onDigit('2')
    holder.onBackspace()
    assertEquals("1.00", holder.state.value.amountDisplay)

    holder.onClear()
    assertEquals("0.00", holder.state.value.amountDisplay)
    assertFalse(holder.state.value.canSave)
  }

  // ---------- 收支方向与分类 ----------

  @Test
  fun switching_type_swaps_categories_and_reselects() = stateHolderTest {
    val holder = newHolder()
    advanceUntilIdle()

    holder.onTypeChange(isExpense = false)

    val state = holder.state.value
    assertFalse(state.isExpense)
    assertEquals(listOf("工资"), state.categories.map { it.name })
    assertTrue("切换方向后必须重新选中同方向的分类", state.categories.first().isSelected)
  }

  @Test
  fun switching_back_restores_expense_categories() = stateHolderTest {
    val holder = newHolder()
    advanceUntilIdle()

    holder.onTypeChange(isExpense = false)
    holder.onTypeChange(isExpense = true)

    assertEquals(listOf("餐饮", "交通"), holder.state.value.categories.map { it.name })
  }

  @Test
  fun selecting_category_moves_the_highlight() = stateHolderTest {
    val holder = newHolder()
    advanceUntilIdle()

    val transport = holder.state.value.categories.first { it.name == "交通" }
    holder.onCategorySelect(transport.id)

    val state = holder.state.value
    assertTrue(state.categories.first { it.name == "交通" }.isSelected)
    assertFalse(state.categories.first { it.name == "餐饮" }.isSelected)
  }

  // ---------- 保存 ----------

  @Test
  fun save_converts_yuan_input_into_minor_units() = stateHolderTest {
    val repository = FakeLedgerRepository()
    val holder = newHolder(repository)
    advanceUntilIdle()

    listOf('1', '2').forEach(holder::onDigit)
    holder.onDot()
    listOf('3', '4').forEach(holder::onDigit)
    holder.save()
    advanceUntilIdle()

    val saved = repository.added
    assertNotNull(saved)
    assertEquals("12.34 元必须存成 1234 分", 1234L, saved!!.amountMinor)
    assertEquals(TransactionType.EXPENSE, saved.type)
    assertTrue("保存成功后应通知界面返回", holder.state.value.finished)
  }

  @Test
  fun save_trims_blank_note_into_null() = stateHolderTest {
    val repository = FakeLedgerRepository()
    val holder = newHolder(repository)
    advanceUntilIdle()

    holder.onDigit('5')
    holder.onNoteChange("   ")
    holder.save()
    advanceUntilIdle()

    assertNull("纯空白备注应存为 null，而不是空字符串", repository.added!!.note)
  }

  @Test
  fun save_uses_clock_value_as_occurred_at() = stateHolderTest {
    val repository = FakeLedgerRepository()
    val holder = newHolder(repository)
    advanceUntilIdle()

    holder.onDigit('5')
    holder.save()
    advanceUntilIdle()

    assertEquals(FIXED_NOW, repository.added!!.occurredAt)
  }

  @Test
  fun save_with_zero_amount_reports_error_and_writes_nothing() = stateHolderTest {
    val repository = FakeLedgerRepository()
    val holder = newHolder(repository)
    advanceUntilIdle()

    holder.onDigit('0')
    holder.save()
    advanceUntilIdle()

    assertNotNull(holder.state.value.errorMessage)
    assertNull(repository.added)
    assertFalse(holder.state.value.finished)
  }

  @Test
  fun save_without_any_category_reports_error() = stateHolderTest {
    val repository = FakeLedgerRepository(categories = emptyList())
    val holder = newHolder(repository)
    advanceUntilIdle()

    holder.onDigit('5')
    holder.save()
    advanceUntilIdle()

    assertNotNull(holder.state.value.errorMessage)
    assertNull(repository.added)
  }

  // ---------- 编辑已有账目（决策 3）----------

  @Test
  fun editing_loads_existing_values() = stateHolderTest {
    val existing =
      TransactionEntity(
        id = 7,
        amountMinor = 8888,
        type = TransactionType.INCOME,
        categoryId = 3,
        note = "年终奖",
        occurredAt = FIXED_NOW,
        createdAt = 1,
        updatedAt = 1,
      )
    val holder = newHolder(FakeLedgerRepository(existingTransaction = existing), entryId = 7)
    advanceUntilIdle()

    val state = holder.state.value
    assertTrue(state.isEditing)
    assertEquals("编辑账目", state.title)
    assertEquals("88.88", state.amountDisplay)
    assertEquals("年终奖", state.note)
    assertFalse(state.isExpense)
    assertTrue("应选中该账目原有的分类", state.categories.first { it.id == 3L }.isSelected)
    assertTrue(state.canSave)
  }

  @Test
  fun editing_then_saving_updates_instead_of_inserting() = stateHolderTest {
    val existing =
      TransactionEntity(
        id = 7,
        amountMinor = 100,
        type = TransactionType.EXPENSE,
        categoryId = 1,
        note = null,
        occurredAt = FIXED_NOW,
        createdAt = 1,
        updatedAt = 1,
      )
    val repository = FakeLedgerRepository(existingTransaction = existing)
    val holder = newHolder(repository, entryId = 7)
    advanceUntilIdle()

    holder.onClear()
    holder.onDigit('9')
    holder.save()
    advanceUntilIdle()

    assertNull("编辑模式不应走新增分支", repository.added)
    assertNotNull(repository.updated)
    assertEquals(7L, repository.updated!!.id)
    assertEquals(900L, repository.updated!!.amountMinor)
  }

  @Test
  fun delete_removes_the_entry() = stateHolderTest {
    val existing =
      TransactionEntity(
        id = 7,
        amountMinor = 100,
        type = TransactionType.EXPENSE,
        categoryId = 1,
        note = null,
        occurredAt = FIXED_NOW,
        createdAt = 1,
        updatedAt = 1,
      )
    val repository = FakeLedgerRepository(existingTransaction = existing)
    val holder = newHolder(repository, entryId = 7)
    advanceUntilIdle()

    holder.delete()
    advanceUntilIdle()

    assertEquals(7L, repository.deletedId)
    assertTrue(holder.state.value.finished)
  }

  // ---------- 日期选择（DatePicker 的 UTC 语义陷阱）----------

  @Test
  fun picking_a_date_uses_that_local_date_and_keeps_time_of_day() = stateHolderTest {
    val repository = FakeLedgerRepository()
    val holder = newHolder(repository, nowMillis = FIXED_NOW)
    advanceUntilIdle()

    val original = Instant.ofEpochMilli(FIXED_NOW).atZone(zone)
    val pickedDate = original.toLocalDate().plusDays(3)
    // DatePicker 返回的是所选日期在 UTC 时区的零点毫秒值
    val utcMidnight = pickedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    holder.onDateSelected(utcMidnight)
    holder.onDigit('5')
    holder.save()
    advanceUntilIdle()

    val saved = Instant.ofEpochMilli(repository.added!!.occurredAt).atZone(zone)
    assertEquals("日期必须是用户所选那天，不能因 UTC 语义整体偏移一天", pickedDate, saved.toLocalDate())
    assertEquals("时分秒应保持原值", original.hour, saved.hour)
    assertEquals(original.minute, saved.minute)
  }

  @Test
  fun date_label_reflects_selected_date() = stateHolderTest {
    val holder = newHolder(nowMillis = FIXED_NOW)
    advanceUntilIdle()

    val original = Instant.ofEpochMilli(FIXED_NOW).atZone(zone).toLocalDate()
    val pickedDate = original.plusDays(3)
    val utcMidnight = pickedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    holder.onDateSelected(utcMidnight)

    val expected = "${pickedDate.year}年${pickedDate.monthValue}月${pickedDate.dayOfMonth}日"
    assertEquals(expected, holder.state.value.dateLabel)
  }
}

/** 固定时间戳：2026-10-10 14:30（上海）。刻意选非零点，才能验出时区换算错误。 */
private val FIXED_NOW: Long =
  LocalDate.of(2026, 10, 10).atTime(14, 30).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

private val DEFAULT_CATEGORIES =
  listOf(
    CategoryEntity(id = 1, name = "餐饮", iconKey = "food", type = TransactionType.EXPENSE, sortOrder = 0),
    CategoryEntity(id = 2, name = "交通", iconKey = "transport", type = TransactionType.EXPENSE, sortOrder = 1),
    CategoryEntity(id = 3, name = "工资", iconKey = "salary", type = TransactionType.INCOME, sortOrder = 0),
  )

private class FakeLedgerRepository(
  private val categories: List<CategoryEntity> = DEFAULT_CATEGORIES,
  private val existingTransaction: TransactionEntity? = null,
) : LedgerRepository {

  private val categoriesFlow = MutableStateFlow(categories)

  var added: TransactionEntity? = null
    private set
  var updated: TransactionEntity? = null
    private set
  var deletedId: Long? = null
    private set

  override fun observeCategories(type: TransactionType?): Flow<List<CategoryEntity>> =
    categoriesFlow.map { list ->
      list.filter { !it.isArchived && (type == null || it.type == type) }
    }

  override fun observeAllCategories(): Flow<List<CategoryEntity>> = categoriesFlow

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

  override suspend fun findTransaction(id: Long): TransactionEntity? =
    existingTransaction?.takeIf { it.id == id }

  override suspend fun addTransaction(
    amountMinor: Long,
    type: TransactionType,
    categoryId: Long,
    note: String?,
    occurredAt: Long,
  ): Long {
    added =
      TransactionEntity(
        id = 100,
        amountMinor = amountMinor,
        type = type,
        categoryId = categoryId,
        note = note,
        occurredAt = occurredAt,
        createdAt = 0L,
        updatedAt = 0L,
      )
    return 100L
  }

  override suspend fun updateTransaction(
    id: Long,
    amountMinor: Long,
    type: TransactionType,
    categoryId: Long,
    note: String?,
    occurredAt: Long,
  ) {
    updated =
      TransactionEntity(
        id = id,
        amountMinor = amountMinor,
        type = type,
        categoryId = categoryId,
        note = note,
        occurredAt = occurredAt,
        createdAt = 0L,
        updatedAt = 0L,
      )
  }

  override suspend fun deleteTransaction(id: Long) {
    deletedId = id
  }

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
