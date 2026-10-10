package com.moneytracker.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类预算校验测试（决策 2 的核心逻辑）。
 *
 * 这是纯函数，不需要数据库，因此可以快速覆盖各种边界。
 */
class BudgetValidationTest {

  @Test
  fun within_total_is_allowed() {
    val result =
      evaluateCategoryBudgetSave(
        totalMinor = 100_000L,
        otherCategoriesSumMinor = 30_000L,
        requestedMinor = 20_000L,
        allowOverBudget = false,
      )
    assertEquals(BudgetSaveResult.Saved, result)
  }

  @Test
  fun exactly_reaching_total_is_allowed() {
    // 边界：之和恰好等于总预算不算超支（实现用 > 而非 >=）
    val result =
      evaluateCategoryBudgetSave(
        totalMinor = 100_000L,
        otherCategoriesSumMinor = 80_000L,
        requestedMinor = 20_000L,
        allowOverBudget = false,
      )
    assertEquals(BudgetSaveResult.Saved, result)
  }

  @Test
  fun exceeding_total_is_rejected_and_reports_numbers() {
    val result =
      evaluateCategoryBudgetSave(
        totalMinor = 100_000L,
        otherCategoriesSumMinor = 90_000L,
        requestedMinor = 20_000L,
        allowOverBudget = false,
      )
    assertTrue(result is BudgetSaveResult.ExceedsTotal)
    val exceeds = result as BudgetSaveResult.ExceedsTotal
    assertEquals(100_000L, exceeds.totalMinor)
    assertEquals(110_000L, exceeds.requestedSumMinor)
  }

  @Test
  fun exceeding_total_is_allowed_when_forced() {
    val result =
      evaluateCategoryBudgetSave(
        totalMinor = 100_000L,
        otherCategoriesSumMinor = 90_000L,
        requestedMinor = 20_000L,
        allowOverBudget = true,
      )
    assertEquals(BudgetSaveResult.Saved, result)
  }

  @Test
  fun no_total_budget_means_no_constraint() {
    val result =
      evaluateCategoryBudgetSave(
        totalMinor = null,
        otherCategoriesSumMinor = 999_999_900L,
        requestedMinor = 999_999_900L,
        allowOverBudget = false,
      )
    assertEquals(BudgetSaveResult.Saved, result)
  }

  @Test
  fun valid_budget_amount_rejects_zero_and_negative() {
    assertFalse(isValidBudgetAmount(0L))
    assertFalse(isValidBudgetAmount(-1L))
    assertTrue(isValidBudgetAmount(1L))
  }

  @Test
  fun valid_budget_amount_rejects_beyond_max() {
    assertTrue(isValidBudgetAmount(com.moneytracker.app.data.model.Money.MAX_MINOR))
    assertFalse(isValidBudgetAmount(com.moneytracker.app.data.model.Money.MAX_MINOR + 1))
  }
}
