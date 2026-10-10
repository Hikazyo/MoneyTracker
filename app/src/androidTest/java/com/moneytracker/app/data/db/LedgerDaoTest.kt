package com.moneytracker.app.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneytracker.app.data.db.entity.CategoryBudgetEntity
import com.moneytracker.app.data.db.entity.MonthlyBudgetEntity
import com.moneytracker.app.data.db.entity.TransactionEntity
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.model.endExclusiveMillis
import com.moneytracker.app.data.model.startMillis
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据层仪器测试（跑在真实 SQLite 上）。
 *
 * 重点覆盖纯函数测不到的三类东西：
 *  - 种子数据是否真的在建库时写入
 *  - 外键 RESTRICT / CASCADE 行为是否符合设计
 *  - 月度区间查询与聚合 SQL 是否正确
 */
@RunWith(AndroidJUnit4::class)
class LedgerDaoTest {

  private lateinit var db: MoneyTrackerDatabase
  private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

  @Before
  fun setUp() {
    // 走 buildInMemory 而不是自己建库：确保与生产使用同一套种子回调，
    // 否则测试路径与生产不一致，种子数据的问题就测不出来
    db = MoneyTrackerDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
  }

  @After
  fun tearDown() {
    db.close()
  }

  private val october = YearMonth.of(2026, 10)

  private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
    LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

  private fun YearMonth.range(): Pair<Long, Long> = startMillis(zone) to endExclusiveMillis(zone)

  private suspend fun firstExpenseCategory() =
    db.categoryDao().observeActiveByType(TransactionType.EXPENSE).first().first()

  private suspend fun insertEntry(
    categoryId: Long,
    amountMinor: Long,
    type: TransactionType = TransactionType.EXPENSE,
    occurredAt: Long = at(2026, 10, 5),
    note: String? = null,
  ): Long =
    db.transactionDao()
      .insert(
        TransactionEntity(
          amountMinor = amountMinor,
          type = type,
          categoryId = categoryId,
          note = note,
          occurredAt = occurredAt,
          createdAt = 1_000L,
          updatedAt = 1_000L,
        )
      )

  // ---------- 种子数据 ----------

  @Test
  fun seed_categories_are_written_on_database_creation() = runTest {
    val active = db.categoryDao().observeActive().first()
    assertEquals(SeedData.ALL.size, active.size)
    assertTrue("种子分类都应标记为内置", active.all { it.isBuiltIn })
    assertTrue("必须有支出分类", active.any { it.type == TransactionType.EXPENSE })
    assertTrue("必须有收入分类", active.any { it.type == TransactionType.INCOME })

    // sortOrder 只要求在**同一方向内**唯一：查询是 ORDER BY type, sortOrder, id，
    // 两个方向的排序值永远不会互相比较。分类的排序位本来就是按方向各自编号的
    // （见 CategoryDao.nextSortOrder(type)），断言全局唯一会把正确做法判成错误。
    TransactionType.entries.forEach { type ->
      val orders = active.filter { it.type == type }.map { it.sortOrder }
      assertEquals(
        "$type 方向内 sortOrder 必须唯一，否则列表顺序不稳定",
        orders.size,
        orders.distinct().size,
      )
    }
  }

  // ---------- 区间查询 ----------

  @Test
  fun range_query_returns_only_entries_of_that_month() = runTest {
    val category = firstExpenseCategory()
    insertEntry(category.id, 100L, occurredAt = at(2026, 9, 30, 23, 59))
    insertEntry(category.id, 200L, occurredAt = at(2026, 10, 1))
    insertEntry(category.id, 300L, occurredAt = at(2026, 10, 31, 23, 59))
    insertEntry(category.id, 400L, occurredAt = at(2026, 11, 1))

    val (start, end) = october.range()
    val entries = db.transactionDao().observeInRange(start, end).first()

    assertEquals(2, entries.size)
    assertEquals(listOf(300L, 200L), entries.map { it.amountMinor })
  }

  @Test
  fun range_query_includes_last_millisecond_of_month() = runTest {
    val category = firstExpenseCategory()
    val lastMs =
      LocalDate.of(2026, 10, 31)
        .atTime(23, 59, 59, 999_000_000)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
    insertEntry(category.id, 777L, occurredAt = lastMs)

    val (start, end) = october.range()
    val entries = db.transactionDao().observeInRange(start, end).first()
    assertEquals(1, entries.size)
    assertEquals(777L, entries.first().amountMinor)
  }

  // ---------- 聚合 ----------

  @Test
  fun sum_separates_expense_and_income() = runTest {
    val expense = firstExpenseCategory()
    val income = db.categoryDao().observeActiveByType(TransactionType.INCOME).first().first()
    insertEntry(expense.id, 1000L, TransactionType.EXPENSE)
    insertEntry(expense.id, 500L, TransactionType.EXPENSE)
    insertEntry(income.id, 8000L, TransactionType.INCOME)

    val (start, end) = october.range()
    assertEquals(1500L, db.transactionDao().observeSum(TransactionType.EXPENSE, start, end).first())
    assertEquals(8000L, db.transactionDao().observeSum(TransactionType.INCOME, start, end).first())
  }

  @Test
  fun sum_returns_zero_when_no_entries() = runTest {
    val (start, end) = october.range()
    assertEquals(0L, db.transactionDao().observeSum(TransactionType.EXPENSE, start, end).first())
  }

  @Test
  fun category_totals_are_grouped_and_sorted_descending() = runTest {
    val categories = db.categoryDao().observeActiveByType(TransactionType.EXPENSE).first()
    val a = categories[0]
    val b = categories[1]
    insertEntry(a.id, 100L)
    insertEntry(a.id, 200L)
    insertEntry(b.id, 500L)

    val (start, end) = october.range()
    val totals =
      db.transactionDao().observeCategoryTotals(TransactionType.EXPENSE, start, end).first()

    assertEquals(2, totals.size)
    assertEquals(b.id, totals[0].categoryId)
    assertEquals(500L, totals[0].totalMinor)
    assertEquals(1, totals[0].entryCount)
    assertEquals(a.id, totals[1].categoryId)
    assertEquals(300L, totals[1].totalMinor)
    assertEquals(2, totals[1].entryCount)
  }

  @Test
  fun entries_carry_their_category_via_relation() = runTest {
    val category = firstExpenseCategory()
    insertEntry(category.id, 100L)

    val (start, end) = october.range()
    val entries = db.transactionDao().observeEntriesInRange(start, end).first()

    assertEquals(1, entries.size)
    assertEquals(category.id, entries.first().category.id)
    assertEquals(category.name, entries.first().category.name)
    assertEquals(category.iconKey, entries.first().category.iconKey)
  }

  // ---------- 增删改 ----------

  @Test
  fun update_changes_fields_but_preserves_created_at() = runTest {
    val category = firstExpenseCategory()
    val id = insertEntry(category.id, 100L, note = "原备注")

    val loaded = db.transactionDao().findById(id)!!
    db.transactionDao().update(loaded.copy(amountMinor = 250L, note = "新备注", updatedAt = 9_999L))

    val after = db.transactionDao().findById(id)!!
    assertEquals(250L, after.amountMinor)
    assertEquals("新备注", after.note)
    assertEquals("createdAt 不应被修改", 1_000L, after.createdAt)
    assertEquals(9_999L, after.updatedAt)
  }

  @Test
  fun delete_removes_entry() = runTest {
    val category = firstExpenseCategory()
    val id = insertEntry(category.id, 100L)
    db.transactionDao().deleteById(id)
    assertNull(db.transactionDao().findById(id))
    assertEquals(0, db.transactionDao().count())
  }

  // ---------- 外键行为 ----------

  @Test
  fun hard_deleting_a_used_category_is_blocked_by_restrict() = runTest {
    val category = firstExpenseCategory()
    insertEntry(category.id, 100L)

    var blocked = false
    try {
      db.openHelper.writableDatabase.execSQL("DELETE FROM categories WHERE id = ${category.id}")
    } catch (e: Exception) {
      blocked = true
    }
    assertTrue("有账目引用的分类必须禁止物理删除（RESTRICT）", blocked)
    assertNotNull(db.categoryDao().findById(category.id))
  }

  @Test
  fun archiving_a_category_keeps_historical_entries_readable() = runTest {
    val category = firstExpenseCategory()
    insertEntry(category.id, 100L)

    db.categoryDao().archive(category.id)

    val selectable = db.categoryDao().observeActiveByType(TransactionType.EXPENSE).first()
    assertTrue("归档分类不应出现在可选列表", selectable.none { it.id == category.id })

    val (start, end) = october.range()
    val entries = db.transactionDao().observeEntriesInRange(start, end).first()
    assertEquals("历史账目仍应显示原分类名", category.name, entries.first().category.name)
  }

  @Test
  fun deleting_a_category_cascades_its_budgets() = runTest {
    val category = firstExpenseCategory()
    db.budgetDao().upsertCategoryBudget(CategoryBudgetEntity("2026-10", category.id, 50_000L, 1L))

    // 该分类没有账目引用，可以物理删除
    db.openHelper.writableDatabase.execSQL("DELETE FROM categories WHERE id = ${category.id}")

    val budgets = db.budgetDao().observeCategoryBudgets("2026-10").first()
    assertTrue("分类删除后其预算应被级联清除", budgets.none { it.categoryId == category.id })
  }

  // ---------- 预算 ----------

  @Test
  fun category_budget_upsert_is_idempotent_for_same_month_and_category() = runTest {
    val category = firstExpenseCategory()
    db.budgetDao().upsertCategoryBudget(CategoryBudgetEntity("2026-10", category.id, 10_000L, 1L))
    db.budgetDao().upsertCategoryBudget(CategoryBudgetEntity("2026-10", category.id, 20_000L, 2L))

    val budgets = db.budgetDao().observeCategoryBudgets("2026-10").first()
    assertEquals("复合主键必须保证同一分类只留一行", 1, budgets.size)
    assertEquals(20_000L, budgets.first().amountMinor)
    assertEquals(20_000L, db.budgetDao().sumCategoryBudgets("2026-10"))
  }

  @Test
  fun monthly_budget_upsert_replaces_previous_value() = runTest {
    db.budgetDao().upsertMonthlyBudget(MonthlyBudgetEntity("2026-10", 100_000L, 1L))
    db.budgetDao().upsertMonthlyBudget(MonthlyBudgetEntity("2026-10", 200_000L, 2L))

    val loaded = db.budgetDao().findMonthlyBudget("2026-10")
    assertNotNull(loaded)
    assertEquals(200_000L, loaded!!.amountMinor)

    db.budgetDao().deleteMonthlyBudget("2026-10")
    assertNull(db.budgetDao().findMonthlyBudget("2026-10"))
  }

  @Test
  fun sum_excluding_ignores_the_edited_category() = runTest {
    val categories = db.categoryDao().observeActiveByType(TransactionType.EXPENSE).first()
    val a = categories[0]
    val b = categories[1]
    db.budgetDao().upsertCategoryBudget(CategoryBudgetEntity("2026-10", a.id, 10_000L, 1L))
    db.budgetDao().upsertCategoryBudget(CategoryBudgetEntity("2026-10", b.id, 30_000L, 1L))

    assertEquals(40_000L, db.budgetDao().sumCategoryBudgets("2026-10"))
    assertEquals(30_000L, db.budgetDao().sumCategoryBudgetsExcluding("2026-10", a.id))
  }

  @Test
  fun budgets_are_scoped_per_month() = runTest {
    val category = firstExpenseCategory()
    db.budgetDao().upsertCategoryBudget(CategoryBudgetEntity("2026-10", category.id, 10_000L, 1L))
    db.budgetDao().upsertCategoryBudget(CategoryBudgetEntity("2026-11", category.id, 20_000L, 1L))

    assertEquals(10_000L, db.budgetDao().sumCategoryBudgets("2026-10"))
    assertEquals(20_000L, db.budgetDao().sumCategoryBudgets("2026-11"))
    assertEquals(0L, db.budgetDao().sumCategoryBudgets("2026-12"))
  }

  // ---------- 分类排序 ----------

  @Test
  fun next_sort_order_continues_after_existing_max() = runTest {
    val expenseMax =
      db.categoryDao().observeActiveByType(TransactionType.EXPENSE).first().maxOf { it.sortOrder }
    assertEquals(expenseMax + 1, db.categoryDao().nextSortOrder(TransactionType.EXPENSE))
  }

  // ---------- 趋势统计的投影查询 ----------

  @Test
  fun amounts_projection_returns_only_rows_inside_range() = runTest {
    val expense = firstExpenseCategory()
    val income = db.categoryDao().observeActiveByType(TransactionType.INCOME).first().first()
    insertEntry(expense.id, 100L, TransactionType.EXPENSE, occurredAt = at(2026, 9, 30, 23, 59))
    insertEntry(expense.id, 200L, TransactionType.EXPENSE, occurredAt = at(2026, 10, 5))
    insertEntry(income.id, 500L, TransactionType.INCOME, occurredAt = at(2026, 10, 6))
    insertEntry(expense.id, 900L, TransactionType.EXPENSE, occurredAt = at(2026, 11, 1))

    val (start, end) = october.range()
    val amounts = db.transactionDao().observeAmountsInRange(start, end).first()

    assertEquals(2, amounts.size)
    assertEquals(setOf(200L, 500L), amounts.map { it.amountMinor }.toSet())
    assertEquals(1, amounts.count { it.type == TransactionType.INCOME })
    assertEquals(
      "投影必须带回 occurredAt，否则无法在 Kotlin 侧按月分桶",
      setOf(at(2026, 10, 5), at(2026, 10, 6)),
      amounts.map { it.occurredAt }.toSet(),
    )
  }

  // ---------- 分类账目数 ----------

  @Test
  fun counts_by_category_groups_entries_per_category() = runTest {
    val categories = db.categoryDao().observeActiveByType(TransactionType.EXPENSE).first()
    val a = categories[0]
    val b = categories[1]
    insertEntry(a.id, 100L)
    insertEntry(a.id, 200L)
    insertEntry(b.id, 300L)

    val counts = db.categoryDao().observeCountsByCategory().first().associate {
      it.categoryId to it.entryCount
    }

    assertEquals(2, counts[a.id])
    assertEquals(1, counts[b.id])
    assertNull("没有账目的分类不应出现（调用方按 0 处理）", counts[categories[2].id])
  }

  @Test
  fun counts_reflect_deletions() = runTest {
    val category = firstExpenseCategory()
    val id = insertEntry(category.id, 100L)
    assertEquals(1, db.categoryDao().observeCountsByCategory().first().single().entryCount)

    db.transactionDao().deleteById(id)

    assertTrue("账目删完后该分类不应再出现在结果里", db.categoryDao().observeCountsByCategory().first().isEmpty())
  }

  @Test
  fun archiving_does_not_change_the_entry_count() = runTest {
    val category = firstExpenseCategory()
    insertEntry(category.id, 100L)

    db.categoryDao().archive(category.id)

    val counts = db.categoryDao().observeCountsByCategory().first().associate {
      it.categoryId to it.entryCount
    }
    assertEquals("归档只是软删除，账目数不变，用户才敢放心归档", 1, counts[category.id])
  }
}
