package com.moneytracker.app.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneytracker.app.data.backup.BackupCodec
import com.moneytracker.app.data.db.MoneyTrackerDatabase
import com.moneytracker.app.data.db.SeedData
import com.moneytracker.app.data.model.MonthKey
import com.moneytracker.app.data.model.TransactionType
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
 * 备份与恢复的端到端测试（真实 SQLite）。
 *
 * 这一层要验证的是纯函数测不到的东西：
 *  - 删除顺序是否满足外键约束（账目对分类是 RESTRICT，先删分类会直接失败）
 *  - 恢复失败时事务是否真的整体回滚，而不是留下半套数据
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {

  private lateinit var db: MoneyTrackerDatabase
  private lateinit var repository: LedgerRepository

  private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
  private val october = MonthKey.of(YearMonth.of(2026, 10))

  private val now: Long =
    LocalDate.of(2026, 10, 10).atTime(14, 30).atZone(zone).toInstant().toEpochMilli()

  @Before
  fun setUp() {
    db = MoneyTrackerDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    repository = DefaultLedgerRepository(db, zone) { now }
  }

  @After
  fun tearDown() {
    db.close()
  }

  private suspend fun firstExpenseCategoryId(): Long =
    db.categoryDao().observeActiveByType(TransactionType.EXPENSE).first().first().id

  // ---------- 生成备份 ----------

  @Test
  fun backup_captures_categories_transactions_and_budgets() = runTest {
    val categoryId = firstExpenseCategoryId()
    repository.addTransaction(3200L, TransactionType.EXPENSE, categoryId, "午饭", now)
    repository.saveMonthlyBudget(october, 100_000L)
    repository.saveCategoryBudget(october, categoryId, 50_000L)

    val backup = repository.createBackup(now)

    assertEquals("应包含全部内置分类", SeedData.ALL.size, backup.categories.size)
    assertEquals(1, backup.transactions.size)
    assertEquals(1, backup.monthlyBudgets.size)
    assertEquals(1, backup.categoryBudgets.size)
    assertEquals(3200L, backup.transactions.single().amountMinor)
    assertEquals("EXPENSE", backup.transactions.single().type)
    assertEquals(now, backup.exportedAt)
  }

  @Test
  fun exported_rows_carry_the_category_name() = runTest {
    val categoryId = firstExpenseCategoryId()
    val categoryName = db.categoryDao().findById(categoryId)!!.name
    repository.addTransaction(3200L, TransactionType.EXPENSE, categoryId, "午饭", now)

    val rows = repository.exportEntries()

    assertEquals(1, rows.size)
    assertEquals(categoryName, rows.single().categoryName)
    assertEquals(3200L, rows.single().amountMinor)
    assertEquals("午饭", rows.single().note)
  }

  // ---------- 恢复：覆盖 ----------

  @Test
  fun restore_replaces_everything_with_the_backup_contents() = runTest {
    val categoryId = firstExpenseCategoryId()
    repository.addTransaction(3200L, TransactionType.EXPENSE, categoryId, "原有的一笔", now)
    val backup = repository.createBackup(now)

    // 把现状改得与备份不同：多一笔账、多一个月度预算
    repository.addTransaction(9900L, TransactionType.EXPENSE, categoryId, "后来加的", now)
    repository.saveMonthlyBudget(october, 500_000L)
    assertEquals(2, db.transactionDao().count())
    assertNotNull(db.budgetDao().findMonthlyBudget(october.value))

    val result = repository.restoreBackup(backup)

    assertTrue(result is RestoreResult.Success)
    assertEquals("恢复后应只剩备份里的那一笔", 1, db.transactionDao().count())
    assertEquals("原有的一笔", db.transactionDao().findById(1L)?.note)
    assertNull("备份里没有的预算必须被清掉", db.budgetDao().findMonthlyBudget(october.value))
  }

  @Test
  fun restore_reports_the_number_of_restored_transactions() = runTest {
    val categoryId = firstExpenseCategoryId()
    repository.addTransaction(3200L, TransactionType.EXPENSE, categoryId, null, now)
    repository.addTransaction(880L, TransactionType.EXPENSE, categoryId, null, now)
    val backup = repository.createBackup(now)

    val result = repository.restoreBackup(backup)

    assertEquals(RestoreResult.Success(2), result)
  }

  @Test
  fun restore_brings_back_deleted_transactions() = runTest {
    val categoryId = firstExpenseCategoryId()
    val id = repository.addTransaction(3200L, TransactionType.EXPENSE, categoryId, "要被删的", now)
    val backup = repository.createBackup(now)

    repository.deleteTransaction(id)
    assertEquals(0, db.transactionDao().count())

    repository.restoreBackup(backup)

    assertEquals(1, db.transactionDao().count())
    assertEquals("要被删的", db.transactionDao().findById(id)?.note)
  }

  // ---------- 恢复：失败必须整体回滚 ----------

  @Test
  fun restore_of_a_corrupt_backup_leaves_existing_data_untouched() = runTest {
    val categoryId = firstExpenseCategoryId()
    repository.addTransaction(3200L, TransactionType.EXPENSE, categoryId, "原有数据", now)
    val good = repository.createBackup(now)

    // 篡改枚举名，让映射阶段抛错
    val broken =
      good.copy(transactions = good.transactions.map { it.copy(type = "NOT_A_TYPE") })

    val result = repository.restoreBackup(broken)

    assertTrue(result is RestoreResult.Failure)
    assertEquals("失败必须整体回滚，原有数据一条都不能少", 1, db.transactionDao().count())
    assertEquals("原有数据", db.transactionDao().findById(1L)?.note)
  }

  @Test
  fun restore_of_a_backup_referencing_a_missing_category_fails_atomically() = runTest {
    val categoryId = firstExpenseCategoryId()
    repository.addTransaction(3200L, TransactionType.EXPENSE, categoryId, "原有数据", now)
    val good = repository.createBackup(now)

    // 保留账目但删掉分类：外键会拒绝，整个事务应回滚
    val broken = good.copy(categories = emptyList())

    val result = repository.restoreBackup(broken)

    assertTrue(result is RestoreResult.Failure)
    assertEquals(1, db.transactionDao().count())
  }

  // ---------- 与编解码串联 ----------

  @Test
  fun backup_survives_a_full_encode_decode_restore_cycle() = runTest {
    val categoryId = firstExpenseCategoryId()
    repository.addTransaction(12_345L, TransactionType.INCOME, categoryId, "工资\"备注", now)
    repository.saveMonthlyBudget(october, 200_000L)
    repository.saveCategoryBudget(october, categoryId, 60_000L)

    val json = BackupCodec.encode(repository.createBackup(now))

    // 清空现状
    db.openHelper.writableDatabase.execSQL("DELETE FROM transactions")
    db.openHelper.writableDatabase.execSQL("DELETE FROM monthly_budgets")
    db.openHelper.writableDatabase.execSQL("DELETE FROM category_budgets")

    val decoded = BackupCodec.decode(json)
    assertTrue(decoded is BackupCodec.DecodeResult.Ok)
    val result = repository.restoreBackup((decoded as BackupCodec.DecodeResult.Ok).backup)

    assertTrue(result is RestoreResult.Success)
    val restored = db.transactionDao().findById(1L)
    assertNotNull(restored)
    assertEquals(12_345L, restored!!.amountMinor)
    assertEquals(TransactionType.INCOME, restored.type)
    assertEquals("工资\"备注", restored.note)
    assertEquals(200_000L, db.budgetDao().findMonthlyBudget(october.value)?.amountMinor)
    assertEquals(60_000L, db.budgetDao().sumCategoryBudgets(october.value))
  }

  @Test
  fun archived_categories_and_their_history_are_preserved() = runTest {
    val categoryId = firstExpenseCategoryId()
    repository.addTransaction(3200L, TransactionType.EXPENSE, categoryId, "归档前的账", now)
    repository.archiveCategory(categoryId)

    val backup = repository.createBackup(now)
    assertTrue(
      "归档状态要进备份",
      backup.categories.first { it.id == categoryId }.isArchived,
    )

    repository.restoreBackup(backup)

    // 归档分类不再出现在可选列表，但历史账目仍能读出分类
    val selectable = db.categoryDao().observeActiveByType(TransactionType.EXPENSE).first()
    assertTrue(selectable.none { it.id == categoryId })
    assertEquals(1, db.transactionDao().count())
  }
}
