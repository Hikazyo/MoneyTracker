package com.moneytracker.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.model.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

  @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY type, sortOrder, id")
  fun observeActive(): Flow<List<CategoryEntity>>

  @Query("SELECT * FROM categories ORDER BY type, sortOrder, id")
  fun observeAll(): Flow<List<CategoryEntity>>

  @Query("SELECT * FROM categories WHERE type = :type AND isArchived = 0 ORDER BY sortOrder, id")
  fun observeActiveByType(type: TransactionType): Flow<List<CategoryEntity>>

  @Query("SELECT * FROM categories WHERE id = :id")
  suspend fun findById(id: Long): CategoryEntity?

  /** 新增分类时取下一个排序位。 */
  @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM categories WHERE type = :type")
  suspend fun nextSortOrder(type: TransactionType): Int

  @Insert suspend fun insert(category: CategoryEntity): Long

  @Update suspend fun update(category: CategoryEntity)

  /** 「删除」分类 = 归档，保留历史账目的分类引用。 */
  @Query("UPDATE categories SET isArchived = 1 WHERE id = :id")
  suspend fun archive(id: Long)

  @Query("UPDATE categories SET isArchived = 0 WHERE id = :id")
  suspend fun unarchive(id: Long)

  /** 该分类下的账目数。改方向、删除之前的安全检查用。 */
  @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :id")
  suspend fun countEntriesFor(id: Long): Int

  /**
   * 删除分类。
   *
   * `isBuiltIn = 0` 刻意写进 WHERE 而不是靠调用方判断：内置分类是导入账单自动归类的
   * 依据（靠 iconKey 对应），少一个就会让那类账目永远匹配不上。把它挡在 SQL 层，
   * 任何调用路径都绕不过去。
   *
   * 返回实际删除的行数，调用方据此判断是否真的删掉了。
   */
  @Query("DELETE FROM categories WHERE id = :id AND isBuiltIn = 0")
  suspend fun deleteCustom(id: Long): Int

  @Query("SELECT COUNT(*) FROM categories")
  suspend fun count(): Int

  // ---------- 备份与恢复 ----------

  @Query("SELECT * FROM categories ORDER BY id ASC")
  suspend fun getAll(): List<CategoryEntity>

  /**
   * 清空分类。
   *
   * 注意外键顺序：账目对分类是 RESTRICT，所以必须**先删账目**再调这里，
   * 否则会因外键约束失败。
   */
  @Query("DELETE FROM categories")
  suspend fun deleteAll()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(categories: List<CategoryEntity>)

  /**
   * 每个分类下的账目数。
   *
   * 用于分类管理页展示，以及归档前提示用户「这个分类下还有 N 笔账目」。
   */
  @Query("SELECT categoryId, COUNT(*) AS entryCount FROM transactions GROUP BY categoryId")
  fun observeCountsByCategory(): Flow<List<CategoryCount>>
}

/** 分类账目数（非实体，仅作查询返回类型）。 */
data class CategoryCount(val categoryId: Long, val entryCount: Int)
