package com.moneytracker.app.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.db.entity.TransactionEntity
import com.moneytracker.app.data.model.AmountAt
import com.moneytracker.app.data.model.TransactionType
import kotlinx.coroutines.flow.Flow

/**
 * 分类聚合结果（非实体，仅作查询返回类型）。
 *
 * 字段名与 SQL 的 AS 别名一一对应，Room 才能正确映射。
 */
data class CategoryTotal(val categoryId: Long, val totalMinor: Long, val entryCount: Int)

/** 账目 + 其所属分类，供列表展示。 */
data class TransactionWithCategory(
  @Embedded val transaction: TransactionEntity,
  @Relation(parentColumn = "categoryId", entityColumn = "id") val category: CategoryEntity,
)

@Dao
interface TransactionDao {

  /**
   * 查询某时间区间（左闭右开）内的账目，按发生时间倒序。
   *
   * 上界用开区间而非闭区间，避免「月末最后一毫秒」被漏掉。
   */
  @Query(
    "SELECT * FROM transactions WHERE occurredAt >= :startMillis AND occurredAt < :endMillis " +
      "ORDER BY occurredAt DESC, id DESC"
  )
  fun observeInRange(startMillis: Long, endMillis: Long): Flow<List<TransactionEntity>>

  /** 同上，但连表带出分类信息。 */
  @Transaction
  @Query(
    "SELECT * FROM transactions WHERE occurredAt >= :startMillis AND occurredAt < :endMillis " +
      "ORDER BY occurredAt DESC, id DESC"
  )
  fun observeEntriesInRange(
    startMillis: Long,
    endMillis: Long,
  ): Flow<List<TransactionWithCategory>>

  @Query(
    "SELECT COALESCE(SUM(amountMinor), 0) FROM transactions " +
      "WHERE type = :type AND occurredAt >= :startMillis AND occurredAt < :endMillis"
  )
  fun observeSum(type: TransactionType, startMillis: Long, endMillis: Long): Flow<Long>

  /** 分类占比统计（M3 图表用）。 */
  @Query(
    "SELECT categoryId AS categoryId, SUM(amountMinor) AS totalMinor, COUNT(*) AS entryCount " +
      "FROM transactions WHERE type = :type AND occurredAt >= :startMillis AND occurredAt < :endMillis " +
      "GROUP BY categoryId ORDER BY totalMinor DESC"
  )
  fun observeCategoryTotals(
    type: TransactionType,
    startMillis: Long,
    endMillis: Long,
  ): Flow<List<CategoryTotal>>

  /**
   * 只取趋势统计需要的三列（金额、方向、时间），避免把备注等无关字段读出来。
   *
   * 分桶在 Kotlin 侧完成（见 TrendAggregator），以保证与列表页使用同一套时区口径。
   */
  @Query(
    "SELECT amountMinor, type, occurredAt FROM transactions " +
      "WHERE occurredAt >= :startMillis AND occurredAt < :endMillis"
  )
  fun observeAmountsInRange(startMillis: Long, endMillis: Long): Flow<List<AmountAt>>

  @Query("SELECT * FROM transactions WHERE id = :id")
  suspend fun findById(id: Long): TransactionEntity?

  @Insert suspend fun insert(entity: TransactionEntity): Long

  @Update suspend fun update(entity: TransactionEntity)

  @Query("DELETE FROM transactions WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("SELECT COUNT(*) FROM transactions")
  suspend fun count(): Int

  // ---------- 导出与备份 ----------

  /** 导出用：全部账目，按时间正序，并带上分类名。 */
  @Transaction
  @Query("SELECT * FROM transactions ORDER BY occurredAt ASC, id ASC")
  suspend fun getAllEntriesWithCategory(): List<TransactionWithCategory>

  @Query("SELECT * FROM transactions ORDER BY id ASC")
  suspend fun getAll(): List<TransactionEntity>

  @Query("DELETE FROM transactions")
  suspend fun deleteAll()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(entities: List<TransactionEntity>)

  /**
   * 批量插入，遇到重复的 `sourceId` 直接跳过。
   *
   * 返回值与入参一一对应：`-1` 表示该条因唯一索引冲突被跳过（即重复导入）。
   * 依赖数据库的唯一索引而不是先查后插，因此不存在并发下的竞态。
   */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIgnoringDuplicates(entities: List<TransactionEntity>): List<Long>
}
