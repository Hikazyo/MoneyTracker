package com.moneytracker.app.data.repository

import com.moneytracker.app.data.backup.BackupFile
import com.moneytracker.app.data.db.dao.CategoryTotal
import com.moneytracker.app.data.db.dao.TransactionWithCategory
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.db.entity.TransactionEntity
import com.moneytracker.app.data.export.ExportEntry
import com.moneytracker.app.data.importer.ImportedEntry
import com.moneytracker.app.data.model.MonthKey
import com.moneytracker.app.data.model.MonthTotal
import com.moneytracker.app.data.model.TransactionType
import kotlinx.coroutines.flow.Flow

/** 某个月的账目全景：明细 + 收支合计。 */
data class MonthLedger(
  val month: MonthKey,
  val entries: List<TransactionWithCategory>,
  val expenseMinor: Long,
  val incomeMinor: Long,
) {
  val balanceMinor: Long get() = incomeMinor - expenseMinor
}

/** 某个月的预算状况。 */
data class MonthBudget(
  val month: MonthKey,
  /** null 表示该月尚未设置总预算。 */
  val totalMinor: Long?,
  /** categoryId -> 预算额度（分） */
  val categoryBudgets: Map<Long, Long>,
  /** categoryId -> 已花金额（分） */
  val spentByCategory: Map<Long, Long>,
) {
  /**
   * 分类预算之和。
   *
   * 进度比例、超支判断都放在 `BudgetMath` 里（有单元测试覆盖）。
   * 这里只保留纯求和，避免同一套算法出现两份实现而慢慢走样。
   */
  val categoryBudgetSumMinor: Long get() = categoryBudgets.values.sum()
}

/** 保存预算的结果。超预算是正常业务状态，因此用返回值表达，而不是抛异常。 */
sealed interface BudgetSaveResult {
  data object Saved : BudgetSaveResult

  data class ExceedsTotal(val totalMinor: Long, val requestedSumMinor: Long) : BudgetSaveResult

  data object InvalidAmount : BudgetSaveResult
}

/** 恢复结果。恢复是破坏性操作，失败必须让用户明确知道，而不是静默半途而废。 */
sealed interface RestoreResult {
  data class Success(val transactionCount: Int) : RestoreResult

  data class Failure(val message: String) : RestoreResult
}

interface LedgerRepository {

  /** 观察启用中的分类；type 为 null 时返回全部。 */
  fun observeCategories(type: TransactionType? = null): Flow<List<CategoryEntity>>

  /** 观察全部分类（含已归档），分类管理页用。 */
  fun observeAllCategories(): Flow<List<CategoryEntity>>

  /** 观察某个月的账目与收支合计。数据变化会自动推送，无需手动刷新。 */
  fun observeMonth(month: MonthKey): Flow<MonthLedger>

  fun observeBudget(month: MonthKey): Flow<MonthBudget>

  /** 某月、某方向的分类聚合，用于统计页的分类占比。 */
  fun observeCategoryTotals(month: MonthKey, type: TransactionType): Flow<List<CategoryTotal>>

  /** 截至 [endMonth]（含）最近 [months] 个月的收支合计，按时间正序，缺月补零。 */
  fun observeMonthlyTrend(endMonth: MonthKey, months: Int): Flow<List<MonthTotal>>

  suspend fun findTransaction(id: Long): TransactionEntity?

  suspend fun addTransaction(
    amountMinor: Long,
    type: TransactionType,
    categoryId: Long,
    note: String?,
    occurredAt: Long,
  ): Long

  /** 修改已有账目（决策 3）。 */
  suspend fun updateTransaction(
    id: Long,
    amountMinor: Long,
    type: TransactionType,
    categoryId: Long,
    note: String?,
    occurredAt: Long,
  )

  /** 删除已有账目（决策 3）。 */
  suspend fun deleteTransaction(id: Long)

  /** 传 null 表示清除该月总预算。 */
  suspend fun saveMonthlyBudget(month: MonthKey, amountMinor: Long?): BudgetSaveResult

  /**
   * 保存分类预算。
   *
   * 传 null 表示清除该分类的预算。当「该月分类预算之和」将超过总预算且
   * [allowOverBudget] 为 false 时，返回 [BudgetSaveResult.ExceedsTotal] 且**不写入**，
   * 由界面决定是否提示用户「仍然保存」。
   */
  suspend fun saveCategoryBudget(
    month: MonthKey,
    categoryId: Long,
    amountMinor: Long?,
    allowOverBudget: Boolean = false,
  ): BudgetSaveResult

  suspend fun addCategory(name: String, iconKey: String, type: TransactionType): Long

  /** 归档分类（软删除），历史账目仍保留其分类引用。 */
  suspend fun archiveCategory(id: Long)

  /** 恢复已归档的分类。 */
  suspend fun unarchiveCategory(id: Long)

  /**
   * 修改分类的名称与图标。
   *
   * 方向不在这里改，见 [changeCategoryType]。名称与图标对已有账目没有任何影响，
   * 所以随时可改；方向会改变「这笔账属于哪个方向」的语义，因此单独设卡。
   */
  suspend fun updateCategory(id: Long, name: String, iconKey: String)

  /**
   * 修改分类方向（支出 ↔ 收入），返回是否改动。
   *
   * **仅当该分类下没有任何账目时才执行。** 账目自带方向，分类也自带方向，
   * 已有账目时把分类方向翻过去，会让账目方向与分类方向对不上，
   * 统计页按方向分组的数字会自相矛盾，而且没有任何报错。
   * 因此这里直接拒绝，而不是默默改掉。
   */
  suspend fun changeCategoryType(id: Long, type: TransactionType): Boolean

  /**
   * 删除自定义分类，返回是否删除。
   *
   * 内置分类不能删（它是导入自动归类的依据），名下还有账目的也不能删
   * （那会让那些账目失去分类）。两种情况都返回 false，由界面提示改用归档。
   */
  suspend fun deleteCategory(id: Long): Boolean

  /** 分类 id -> 账目数。用于分类管理页展示与归档前提示。 */
  fun observeCategoryCounts(): Flow<Map<Long, Int>>

  // ---------- 导出与备份 ----------

  /** 导出用的全部账目（按时间正序，已带上分类名）。 */
  suspend fun exportEntries(): List<ExportEntry>

  /** 生成一份完整备份。 */
  suspend fun createBackup(exportedAt: Long): BackupFile

  /**
   * 用备份**覆盖**现有全部数据。
   *
   * 整个过程在单个事务内完成：中途失败会整体回滚，不会留下半套数据。
   */
  suspend fun restoreBackup(backup: BackupFile): RestoreResult

  // ---------- 导入外部账单 ----------

  /**
   * 导入从微信 / 支付宝账单解析出的账目。
   *
   * @param categoryIdByIconKey 由分类推断出的 iconKey 到实际分类 id 的映射
   * @param fallbackCategoryId 未匹配上分类时归入的分类
   * @return 实际写入条数与因重复而跳过的条数
   */
  suspend fun importEntries(
    entries: List<ImportedEntry>,
    categoryIdByIconKey: Map<String, Long>,
    fallbackCategoryId: Long,
  ): ImportResult
}

/** 导入结果。 */
data class ImportResult(val inserted: Int, val duplicates: Int)
