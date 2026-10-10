package com.moneytracker.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.moneytracker.app.data.db.entity.CategoryBudgetEntity
import com.moneytracker.app.data.db.entity.MonthlyBudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

  // ---------- 总预算 ----------

  @Query("SELECT * FROM monthly_budgets WHERE monthKey = :monthKey")
  fun observeMonthlyBudget(monthKey: String): Flow<MonthlyBudgetEntity?>

  @Query("SELECT * FROM monthly_budgets WHERE monthKey = :monthKey")
  suspend fun findMonthlyBudget(monthKey: String): MonthlyBudgetEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMonthlyBudget(entity: MonthlyBudgetEntity)

  @Query("DELETE FROM monthly_budgets WHERE monthKey = :monthKey")
  suspend fun deleteMonthlyBudget(monthKey: String)

  // ---------- 分类预算 ----------

  @Query("SELECT * FROM category_budgets WHERE monthKey = :monthKey")
  fun observeCategoryBudgets(monthKey: String): Flow<List<CategoryBudgetEntity>>

  @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM category_budgets WHERE monthKey = :monthKey")
  suspend fun sumCategoryBudgets(monthKey: String): Long

  /**
   * 求该月分类预算之和，但排除指定分类。
   *
   * 用于「修改某个分类预算」时的校验：需要判断的是**改完之后**是否超总预算，
   * 所以要先把该分类原有的额度排除，再加上新额度。
   */
  @Query(
    "SELECT COALESCE(SUM(amountMinor), 0) FROM category_budgets " +
      "WHERE monthKey = :monthKey AND categoryId != :excludeCategoryId"
  )
  suspend fun sumCategoryBudgetsExcluding(monthKey: String, excludeCategoryId: Long): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertCategoryBudget(entity: CategoryBudgetEntity)

  @Query("DELETE FROM category_budgets WHERE monthKey = :monthKey AND categoryId = :categoryId")
  suspend fun deleteCategoryBudget(monthKey: String, categoryId: Long)

  // ---------- 备份与恢复 ----------

  @Query("SELECT * FROM monthly_budgets") suspend fun getAllMonthlyBudgets(): List<MonthlyBudgetEntity>

  @Query("DELETE FROM monthly_budgets") suspend fun deleteAllMonthlyBudgets()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMonthlyBudgets(entities: List<MonthlyBudgetEntity>)

  @Query("SELECT * FROM category_budgets")
  suspend fun getAllCategoryBudgets(): List<CategoryBudgetEntity>

  @Query("DELETE FROM category_budgets") suspend fun deleteAllCategoryBudgets()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertCategoryBudgets(entities: List<CategoryBudgetEntity>)
}
