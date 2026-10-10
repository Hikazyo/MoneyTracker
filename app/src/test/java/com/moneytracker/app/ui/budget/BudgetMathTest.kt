package com.moneytracker.app.ui.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预算进度计算测试。
 *
 * 重点在边界：「未设限额」「限额为 0」「恰好用完」这三种情况最容易写错，
 * 而错了以后界面上看不出异常（只会显示错的颜色或错的剩余额）。
 */
class BudgetMathTest {

  @Test
  fun ratio_is_null_when_no_limit_is_set() {
    assertNull(BudgetMath.ratio(spentMinor = 5000, limitMinor = null))
  }

  @Test
  fun ratio_is_null_when_limit_is_zero() {
    // 0 是非法额度，不能被当成「已用满」
    assertNull(BudgetMath.ratio(spentMinor = 5000, limitMinor = 0))
  }

  @Test
  fun ratio_is_null_when_limit_is_negative() {
    assertNull(BudgetMath.ratio(spentMinor = 5000, limitMinor = -100))
  }

  @Test
  fun ratio_is_proportional() {
    assertEquals(0.5f, BudgetMath.ratio(50_000, 100_000)!!, 1e-6f)
    assertEquals(1f, BudgetMath.ratio(100_000, 100_000)!!, 1e-6f)
  }

  @Test
  fun ratio_can_exceed_one_so_callers_can_detect_overspend() {
    // 刻意不在此截断：截断了就分不清「刚好用完」和「超了两倍」
    val ratio = BudgetMath.ratio(200_000, 100_000)!!
    assertEquals(2f, ratio, 1e-6f)
    assertTrue(ratio > 1f)
  }

  @Test
  fun ratio_of_zero_spending_is_zero() {
    assertEquals(0f, BudgetMath.ratio(0, 100_000)!!, 1e-6f)
  }

  @Test
  fun over_budget_is_false_without_limit() {
    assertFalse(BudgetMath.isOverBudget(spentMinor = 999_999, limitMinor = null))
  }

  @Test
  fun spending_exactly_the_limit_is_not_over_budget() {
    // 边界：恰好用完不算超支
    assertFalse(BudgetMath.isOverBudget(spentMinor = 100_000, limitMinor = 100_000))
  }

  @Test
  fun spending_one_cent_over_the_limit_is_over_budget() {
    assertTrue(BudgetMath.isOverBudget(spentMinor = 100_001, limitMinor = 100_000))
  }

  @Test
  fun remaining_is_positive_when_under_budget() {
    assertEquals(50_000L, BudgetMath.remainingMinor(spentMinor = 50_000, limitMinor = 100_000))
  }

  @Test
  fun remaining_is_negative_when_over_budget() {
    // 用负数表示超支了多少，而不是截断为 0——截断会丢失「超了多少」这个信息
    assertEquals(-20_000L, BudgetMath.remainingMinor(spentMinor = 120_000, limitMinor = 100_000))
  }

  @Test
  fun remaining_label_reads_as_remaining_when_under() {
    assertEquals("剩余 500.00", BudgetMath.remainingLabel(spentMinor = 50_000, limitMinor = 100_000))
  }

  @Test
  fun remaining_label_reads_as_over_when_exceeded() {
    assertEquals("超支 200.00", BudgetMath.remainingLabel(spentMinor = 120_000, limitMinor = 100_000))
  }

  @Test
  fun remaining_label_at_exact_limit_shows_zero_remaining() {
    assertEquals("剩余 0.00", BudgetMath.remainingLabel(spentMinor = 100_000, limitMinor = 100_000))
  }

  @Test
  fun progress_label_shows_spent_over_limit_with_grouping() {
    assertEquals("320.00 / 1,000.00", BudgetMath.progressLabel(spentMinor = 32_000, limitMinor = 100_000))
  }

  @Test
  fun percent_label_is_dash_without_limit() {
    assertEquals("—", BudgetMath.percentLabel(spentMinor = 5000, limitMinor = null))
  }

  @Test
  fun percent_label_rounds_to_whole_percent() {
    assertEquals("53%", BudgetMath.percentLabel(32_000, 60_000))
    assertEquals("100%", BudgetMath.percentLabel(100_000, 100_000))
    assertEquals("133%", BudgetMath.percentLabel(200_000, 150_000))
  }
}
