package com.moneytracker.app.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneytracker.app.data.db.MoneyTrackerDatabase
import com.moneytracker.app.data.importer.ImportedEntry
import com.moneytracker.app.data.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 账单导入的持久化测试（真实 SQLite）。
 *
 * 重点是**重复导入**：去重依赖 `sourceId` 上的唯一索引，属于数据库层的行为，
 * 纯函数测试覆盖不到。用户很可能会把同一份月度账单导两次，重复记账会直接毁掉统计。
 */
@RunWith(AndroidJUnit4::class)
class ImportEntriesTest {

  private lateinit var db: MoneyTrackerDatabase
  private lateinit var repository: LedgerRepository

  private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
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

  private fun imported(
    sourceId: String,
    amountMinor: Long = 1000L,
    type: TransactionType = TransactionType.EXPENSE,
    iconKey: String? = null,
    note: String? = "备注",
  ) = ImportedEntry(
    sourceId = sourceId,
    occurredAt = now,
    type = type,
    amountMinor = amountMinor,
    counterparty = "商家",
    product = "商品",
    note = note,
    suggestedIconKey = iconKey,
  )

  private suspend fun foodCategoryId(): Long =
    db.categoryDao().observeActiveByType(TransactionType.EXPENSE).first().first { it.name == "餐饮" }.id

  private suspend fun fallbackCategoryId(): Long =
    db.categoryDao().observeActiveByType(TransactionType.EXPENSE).first().first { it.name == "其他支出" }.id

  private suspend fun iconKeyToId(): Map<String, Long> =
    db.categoryDao().getAll().associate { it.iconKey to it.id }

  @Test
  fun import_writes_entries_into_the_ledger() = runTest {
    val fallback = fallbackCategoryId()
    val result =
      repository.importEntries(
        entries = listOf(imported("WECHAT:1", 3250L, note = "午餐"), imported("WECHAT:2", 400L)),
        categoryIdByIconKey = iconKeyToId(),
        fallbackCategoryId = fallback,
      )

    assertEquals(2, result.inserted)
    assertEquals(0, result.duplicates)
    assertEquals(2, db.transactionDao().count())

    val first = db.transactionDao().findById(1L)
    assertNotNull(first)
    assertEquals(3250L, first!!.amountMinor)
    assertEquals("午餐", first.note)
    assertEquals("WECHAT:1", first.sourceId)
  }

  @Test
  fun re_importing_the_same_file_skips_duplicates() = runTest {
    val fallback = fallbackCategoryId()
    val entries = listOf(imported("WECHAT:1"), imported("WECHAT:2"))

    repository.importEntries(entries, iconKeyToId(), fallback)
    val second = repository.importEntries(entries, iconKeyToId(), fallback)

    assertEquals("第二次不应再写入", 0, second.inserted)
    assertEquals(2, second.duplicates)
    assertEquals("账本里仍然只有两条", 2, db.transactionDao().count())
  }

  @Test
  fun partial_overlap_only_inserts_the_new_ones() = runTest {
    val fallback = fallbackCategoryId()
    repository.importEntries(listOf(imported("WECHAT:1")), iconKeyToId(), fallback)

    // 第二份账单与第一份有一条重叠（常见于跨月导出的边界）
    val second =
      repository.importEntries(
        listOf(imported("WECHAT:1"), imported("WECHAT:3")),
        iconKeyToId(),
        fallback,
      )

    assertEquals(1, second.inserted)
    assertEquals(1, second.duplicates)
    assertEquals(2, db.transactionDao().count())
  }

  @Test
  fun entries_with_a_matched_category_use_it_instead_of_the_fallback() = runTest {
    val fallback = fallbackCategoryId()
    val expected = foodCategoryId()

    repository.importEntries(
      listOf(imported("WECHAT:1", iconKey = "food")),
      iconKeyToId(),
      fallback,
    )

    assertEquals(expected, db.transactionDao().findById(1L)!!.categoryId)
  }

  @Test
  fun unmatched_entries_fall_back_to_the_chosen_category() = runTest {
    val fallback = fallbackCategoryId()

    repository.importEntries(
      listOf(imported("WECHAT:1", iconKey = null)),
      iconKeyToId(),
      fallback,
    )

    assertEquals(fallback, db.transactionDao().findById(1L)!!.categoryId)
  }

  @Test
  fun unknown_suggested_category_falls_back_instead_of_crashing() = runTest {
    // 用户可能把该分类归档了，此时 iconKey 已不在分类表里
    val fallback = fallbackCategoryId()

    repository.importEntries(
      listOf(imported("WECHAT:1", iconKey = "no_such_icon")),
      iconKeyToId(),
      fallback,
    )

    assertEquals(fallback, db.transactionDao().findById(1L)!!.categoryId)
  }

  @Test
  fun income_and_expense_entries_can_use_different_fallbacks() = runTest {
    val expenseFallback = fallbackCategoryId()
    val incomeFallback =
      db.categoryDao().observeActiveByType(TransactionType.INCOME).first().first { it.name == "其他收入" }.id

    repository.importEntries(
      listOf(imported("WECHAT:E", type = TransactionType.EXPENSE)),
      iconKeyToId(),
      expenseFallback,
    )
    repository.importEntries(
      listOf(imported("WECHAT:I", type = TransactionType.INCOME)),
      iconKeyToId(),
      incomeFallback,
    )

    val byType = db.transactionDao().getAll().associate { it.type to it.categoryId }
    assertEquals(expenseFallback, byType[TransactionType.EXPENSE])
    assertEquals(incomeFallback, byType[TransactionType.INCOME])
  }

  @Test
  fun imported_entries_join_the_month_view() = runTest {
    val fallback = fallbackCategoryId()
    repository.importEntries(listOf(imported("WECHAT:1", 3250L)), iconKeyToId(), fallback)

    val month = com.moneytracker.app.data.model.MonthKey.of(java.time.YearMonth.of(2026, 10))
    val ledger = repository.observeMonth(month).first()

    assertEquals(1, ledger.entries.size)
    assertEquals(3250L, ledger.expenseMinor)
  }

  @Test
  fun empty_import_is_a_no_op() = runTest {
    val result = repository.importEntries(emptyList(), emptyMap(), fallbackCategoryId())

    assertEquals(0, result.inserted)
    assertEquals(0, result.duplicates)
    assertEquals(0, db.transactionDao().count())
  }

  @Test
  fun manually_added_entries_are_unaffected_by_import_deduplication() = runTest {
    // 手工记账的 sourceId 为 null；SQLite 唯一索引对 NULL 不生效，不能被误判为重复
    val categoryId = foodCategoryId()
    repository.addTransaction(1000L, TransactionType.EXPENSE, categoryId, "手工一", now)
    repository.addTransaction(2000L, TransactionType.EXPENSE, categoryId, "手工二", now)

    repository.importEntries(listOf(imported("WECHAT:1")), iconKeyToId(), categoryId)

    assertEquals(3, db.transactionDao().count())
  }
}
