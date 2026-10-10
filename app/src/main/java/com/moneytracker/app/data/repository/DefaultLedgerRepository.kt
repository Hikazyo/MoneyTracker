package com.moneytracker.app.data.repository

import androidx.room.withTransaction
import com.moneytracker.app.data.backup.BackupCodec
import com.moneytracker.app.data.backup.BackupFile
import com.moneytracker.app.data.backup.toBackup
import com.moneytracker.app.data.backup.toEntity
import com.moneytracker.app.data.db.MoneyTrackerDatabase
import com.moneytracker.app.data.db.dao.CategoryTotal
import com.moneytracker.app.data.db.entity.CategoryBudgetEntity
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.db.entity.MonthlyBudgetEntity
import com.moneytracker.app.data.db.entity.TransactionEntity
import com.moneytracker.app.data.export.ExportEntry
import com.moneytracker.app.data.importer.ImportedEntry
import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.model.MonthKey
import com.moneytracker.app.data.model.MonthTotal
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.model.TrendAggregator
import com.moneytracker.app.data.model.endExclusiveMillis
import com.moneytracker.app.data.model.startMillis
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 分类预算校验的纯函数形式。
 *
 * 抽出来是为了能不经数据库直接单元测试——这是决策 2 的核心逻辑，
 * 也是最该被测试覆盖的部分。
 */
internal fun evaluateCategoryBudgetSave(
  totalMinor: Long?,
  otherCategoriesSumMinor: Long,
  requestedMinor: Long,
  allowOverBudget: Boolean,
): BudgetSaveResult {
  val newSum = otherCategoriesSumMinor + requestedMinor
  return if (totalMinor != null && newSum > totalMinor && !allowOverBudget) {
    BudgetSaveResult.ExceedsTotal(totalMinor, newSum)
  } else {
    BudgetSaveResult.Saved
  }
}

/** 金额是否是可保存的合法额度（正数且不超上限）。 */
internal fun isValidBudgetAmount(amountMinor: Long): Boolean =
  amountMinor > 0L && amountMinor <= Money.MAX_MINOR

class DefaultLedgerRepository(
  private val db: MoneyTrackerDatabase,
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val clock: () -> Long = System::currentTimeMillis,
) : LedgerRepository {

  private val categoryDao = db.categoryDao()
  private val transactionDao = db.transactionDao()
  private val budgetDao = db.budgetDao()

  override fun observeCategories(type: TransactionType?): Flow<List<CategoryEntity>> =
    if (type == null) categoryDao.observeActive() else categoryDao.observeActiveByType(type)

  override fun observeAllCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

  override fun observeMonth(month: MonthKey): Flow<MonthLedger> {
    val ym = month.toYearMonth()
    val start = ym.startMillis(zone)
    val end = ym.endExclusiveMillis(zone)
    return combine(
      transactionDao.observeEntriesInRange(start, end),
      transactionDao.observeSum(TransactionType.EXPENSE, start, end),
      transactionDao.observeSum(TransactionType.INCOME, start, end),
    ) { entries, expense, income -> MonthLedger(month, entries, expense, income) }
  }

  override fun observeBudget(month: MonthKey): Flow<MonthBudget> {
    val ym = month.toYearMonth()
    val start = ym.startMillis(zone)
    val end = ym.endExclusiveMillis(zone)
    return combine(
      budgetDao.observeMonthlyBudget(month.value),
      budgetDao.observeCategoryBudgets(month.value),
      transactionDao.observeCategoryTotals(TransactionType.EXPENSE, start, end),
    ) { total, categoryBudgets, totals ->
      MonthBudget(
        month = month,
        totalMinor = total?.amountMinor,
        categoryBudgets = categoryBudgets.associate { it.categoryId to it.amountMinor },
        spentByCategory = totals.associate { it.categoryId to it.totalMinor },
      )
    }
  }

  override fun observeCategoryTotals(
    month: MonthKey,
    type: TransactionType,
  ): Flow<List<CategoryTotal>> {
    val ym = month.toYearMonth()
    return transactionDao.observeCategoryTotals(
      type,
      ym.startMillis(zone),
      ym.endExclusiveMillis(zone),
    )
  }

  override fun observeMonthlyTrend(endMonth: MonthKey, months: Int): Flow<List<MonthTotal>> {
    val keys = TrendAggregator.monthRange(endMonth, months)
    val startYm = keys.first().toYearMonth()
    val endYm = keys.last().toYearMonth()
    return transactionDao
      .observeAmountsInRange(startYm.startMillis(zone), endYm.endExclusiveMillis(zone))
      // 分桶放在 Kotlin 侧，复用与列表页相同的时区口径
      .map { entries -> TrendAggregator.bucket(entries, keys, zone) }
  }

  override suspend fun findTransaction(id: Long): TransactionEntity? = transactionDao.findById(id)

  override suspend fun addTransaction(
    amountMinor: Long,
    type: TransactionType,
    categoryId: Long,
    note: String?,
    occurredAt: Long,
  ): Long {
    val now = clock()
    return transactionDao.insert(
      TransactionEntity(
        amountMinor = amountMinor,
        type = type,
        categoryId = categoryId,
        note = note?.trim()?.takeIf { it.isNotEmpty() },
        occurredAt = occurredAt,
        createdAt = now,
        updatedAt = now,
      )
    )
  }

  override suspend fun updateTransaction(
    id: Long,
    amountMinor: Long,
    type: TransactionType,
    categoryId: Long,
    note: String?,
    occurredAt: Long,
  ) {
    // 先读后写：只更新业务字段，createdAt 必须原样保留
    val existing = transactionDao.findById(id) ?: return
    transactionDao.update(
      existing.copy(
        amountMinor = amountMinor,
        type = type,
        categoryId = categoryId,
        note = note?.trim()?.takeIf { it.isNotEmpty() },
        occurredAt = occurredAt,
        updatedAt = clock(),
      )
    )
  }

  override suspend fun deleteTransaction(id: Long) {
    transactionDao.deleteById(id)
  }

  override suspend fun saveMonthlyBudget(
    month: MonthKey,
    amountMinor: Long?,
  ): BudgetSaveResult {
    if (amountMinor == null) {
      budgetDao.deleteMonthlyBudget(month.value)
      return BudgetSaveResult.Saved
    }
    if (!isValidBudgetAmount(amountMinor)) return BudgetSaveResult.InvalidAmount
    budgetDao.upsertMonthlyBudget(
      MonthlyBudgetEntity(monthKey = month.value, amountMinor = amountMinor, updatedAt = clock())
    )
    return BudgetSaveResult.Saved
  }

  override suspend fun saveCategoryBudget(
    month: MonthKey,
    categoryId: Long,
    amountMinor: Long?,
    allowOverBudget: Boolean,
  ): BudgetSaveResult {
    if (amountMinor != null && !isValidBudgetAmount(amountMinor)) {
      return BudgetSaveResult.InvalidAmount
    }

    // 校验与写入放在同一事务内：否则并发保存时，两次都读到旧值，可能双双通过校验
    return db.withTransaction {
      if (amountMinor == null) {
        budgetDao.deleteCategoryBudget(month.value, categoryId)
        return@withTransaction BudgetSaveResult.Saved
      }

      val total = budgetDao.findMonthlyBudget(month.value)?.amountMinor
      // 判断的是「改完之后」是否超总预算，所以要排除该分类原有的额度
      val othersSum = budgetDao.sumCategoryBudgetsExcluding(month.value, categoryId)
      val verdict = evaluateCategoryBudgetSave(total, othersSum, amountMinor, allowOverBudget)

      if (verdict is BudgetSaveResult.Saved) {
        budgetDao.upsertCategoryBudget(
          CategoryBudgetEntity(
            monthKey = month.value,
            categoryId = categoryId,
            amountMinor = amountMinor,
            updatedAt = clock(),
          )
        )
      }
      verdict
    }
  }

  override suspend fun addCategory(name: String, iconKey: String, type: TransactionType): Long {
    val order = categoryDao.nextSortOrder(type)
    return categoryDao.insert(
      CategoryEntity(
        name = name.trim(),
        iconKey = iconKey,
        type = type,
        sortOrder = order,
        isBuiltIn = false,
        isArchived = false,
      )
    )
  }

  override suspend fun archiveCategory(id: Long) {
    categoryDao.archive(id)
  }

  override suspend fun unarchiveCategory(id: Long) {
    categoryDao.unarchive(id)
  }

  override suspend fun updateCategory(id: Long, name: String, iconKey: String) {
    val existing = categoryDao.findById(id) ?: return
    categoryDao.update(existing.copy(name = name.trim(), iconKey = iconKey))
  }

  override suspend fun changeCategoryType(id: Long, type: TransactionType): Boolean {
    val existing = categoryDao.findById(id) ?: return false
    if (existing.type == type) return false
    // 安全检查放在仓库层而不是界面：界面上的开关与真正写库之间隔着一次异步往返，
    // 期间完全可能在别处给这个分类记了一笔。
    if (categoryDao.countEntriesFor(id) > 0) return false

    // 换方向后要重新排到目标方向的末尾，否则 sortOrder 会和该方向已有分类撞车
    categoryDao.update(existing.copy(type = type, sortOrder = categoryDao.nextSortOrder(type)))
    return true
  }

  override suspend fun deleteCategory(id: Long): Boolean {
    val existing = categoryDao.findById(id) ?: return false
    if (existing.isBuiltIn) return false
    if (categoryDao.countEntriesFor(id) > 0) return false

    // 上面两道检查已经挡住会被外键拒绝的情况；这里再兜一次底，
    // 因为并发下仍可能刚好插进一笔账目，而删除失败会以异常形式冒出来。
    return runCatching { categoryDao.deleteCustom(id) > 0 }.getOrDefault(false)
  }

  override fun observeCategoryCounts(): Flow<Map<Long, Int>> =
    categoryDao.observeCountsByCategory().map { counts ->
      counts.associate { it.categoryId to it.entryCount }
    }

  // ---------- 导出与备份 ----------

  override suspend fun exportEntries(): List<ExportEntry> =
    transactionDao.getAllEntriesWithCategory().map { row ->
      ExportEntry(
        occurredAt = row.transaction.occurredAt,
        type = row.transaction.type,
        categoryName = row.category.name,
        amountMinor = row.transaction.amountMinor,
        note = row.transaction.note,
      )
    }

  override suspend fun createBackup(exportedAt: Long): BackupFile =
    BackupFile(
      schemaVersion = BackupCodec.CURRENT_SCHEMA_VERSION,
      exportedAt = exportedAt,
      categories = categoryDao.getAll().map { it.toBackup() },
      transactions = transactionDao.getAll().map { it.toBackup() },
      monthlyBudgets = budgetDao.getAllMonthlyBudgets().map { it.toBackup() },
      categoryBudgets = budgetDao.getAllCategoryBudgets().map { it.toBackup() },
    )

  override suspend fun restoreBackup(backup: BackupFile): RestoreResult =
    try {
      db.withTransaction {
        // 删除顺序受外键约束制约：账目对分类是 RESTRICT，必须先删账目再删分类
        transactionDao.deleteAll()
        budgetDao.deleteAllCategoryBudgets()
        categoryDao.deleteAll()
        budgetDao.deleteAllMonthlyBudgets()

        categoryDao.insertAll(backup.categories.map { it.toEntity() })
        transactionDao.insertAll(backup.transactions.map { it.toEntity() })
        budgetDao.insertMonthlyBudgets(backup.monthlyBudgets.map { it.toEntity() })
        budgetDao.insertCategoryBudgets(backup.categoryBudgets.map { it.toEntity() })
      }
      RestoreResult.Success(backup.transactions.size)
    } catch (e: Exception) {
      // 事务已回滚，现有数据保持不变——这一点必须让用户知道，否则他以为数据没了
      RestoreResult.Failure(e.message ?: "恢复失败")
    }

  override suspend fun importEntries(
    entries: List<ImportedEntry>,
    categoryIdByIconKey: Map<String, Long>,
    fallbackCategoryId: Long,
  ): ImportResult {
    if (entries.isEmpty()) return ImportResult(inserted = 0, duplicates = 0)

    val now = clock()
    val entities =
      entries.map { entry ->
        TransactionEntity(
          amountMinor = entry.amountMinor,
          type = entry.type,
          // 猜出的分类若不在用户的分类表里（例如已被归档），同样落到兜底分类
          categoryId = entry.suggestedIconKey?.let { categoryIdByIconKey[it] } ?: fallbackCategoryId,
          note = entry.note,
          occurredAt = entry.occurredAt,
          createdAt = now,
          updatedAt = now,
          sourceId = entry.sourceId,
        )
      }

    val rowIds =
      db.withTransaction { transactionDao.insertIgnoringDuplicates(entities) }
    val inserted = rowIds.count { it != -1L }
    return ImportResult(inserted = inserted, duplicates = rowIds.size - inserted)
  }
}
