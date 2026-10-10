package com.moneytracker.app.data.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 趋势分桶测试。
 *
 * 重点：缺月必须补零（否则柱状图会错位）、跨月边界必须按本地时区判定。
 */
class TrendAggregatorTest {

  private val shanghai = ZoneId.of("Asia/Shanghai")

  private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
    LocalDate.of(year, month, day).atTime(hour, 0).atZone(shanghai).toInstant().toEpochMilli()

  private fun expense(minor: Long, occurredAt: Long) =
    AmountAt(minor, TransactionType.EXPENSE, occurredAt)

  private fun income(minor: Long, occurredAt: Long) =
    AmountAt(minor, TransactionType.INCOME, occurredAt)

  // ---------- 月份区间 ----------

  @Test
  fun month_range_is_contiguous_and_ascending() {
    val range = TrendAggregator.monthRange(MonthKey.of(YearMonth.of(2026, 10)), 6)
    assertEquals(
      listOf("2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10"),
      range.map { it.value },
    )
  }

  @Test
  fun month_range_crosses_year_boundary() {
    val range = TrendAggregator.monthRange(MonthKey.of(YearMonth.of(2027, 2)), 4)
    assertEquals(listOf("2026-11", "2026-12", "2027-01", "2027-02"), range.map { it.value })
  }

  @Test
  fun single_month_range_contains_only_that_month() {
    val range = TrendAggregator.monthRange(MonthKey.of(YearMonth.of(2026, 10)), 1)
    assertEquals(listOf("2026-10"), range.map { it.value })
  }

  @Test(expected = IllegalArgumentException::class)
  fun month_range_rejects_non_positive_count() {
    TrendAggregator.monthRange(MonthKey.of(YearMonth.of(2026, 10)), 0)
  }

  // ---------- 分桶 ----------

  @Test
  fun sums_expense_and_income_separately_per_month() {
    val months =
      TrendAggregator.monthRange(MonthKey.of(YearMonth.of(2026, 10)), 2) // 9月, 10月
    val totals =
      TrendAggregator.bucket(
        listOf(
          expense(1000, at(2026, 9, 10)),
          expense(2000, at(2026, 9, 20)),
          income(5000, at(2026, 9, 25)),
          expense(300, at(2026, 10, 1)),
          income(8000, at(2026, 10, 1)),
        ),
        months,
        shanghai,
      )

    assertEquals(2, totals.size)
    assertEquals("2026-09", totals[0].month.value)
    assertEquals(3000L, totals[0].expenseMinor)
    assertEquals(5000L, totals[0].incomeMinor)
    assertEquals("2026-10", totals[1].month.value)
    assertEquals(300L, totals[1].expenseMinor)
    assertEquals(8000L, totals[1].incomeMinor)
  }

  @Test
  fun months_without_entries_are_filled_with_zero() {
    // 缺月补零是刚需：否则中间空一个月，柱状图的月份就会整体错位
    val months = TrendAggregator.monthRange(MonthKey.of(YearMonth.of(2026, 10)), 4)
    val totals =
      TrendAggregator.bucket(listOf(expense(500, at(2026, 7, 15))), months, shanghai)

    assertEquals(4, totals.size)
    assertEquals(500L, totals[0].expenseMinor) // 7月
    assertEquals(0L, totals[1].expenseMinor) // 8月缺
    assertEquals(0L, totals[2].expenseMinor) // 9月缺
    assertEquals(0L, totals[3].expenseMinor)
  }

  @Test
  fun entries_outside_the_range_are_ignored() {
    val months = TrendAggregator.monthRange(MonthKey.of(YearMonth.of(2026, 10)), 1)
    val totals =
      TrendAggregator.bucket(
        listOf(expense(100, at(2026, 9, 30)), expense(200, at(2026, 10, 15)), expense(300, at(2026, 11, 1))),
        months,
        shanghai,
      )

    assertEquals(1, totals.size)
    assertEquals(200L, totals[0].expenseMinor)
  }

  @Test
  fun bucketing_follows_local_timezone_not_utc() {
    // 2026-11-01 00:30（上海）= 2026-10-31 16:30 UTC。
    // 按上海时区应归入 11 月；若错用 UTC 就会落到 10 月。
    val boundary = at(2026, 11, 1, hour = 0).plus(30 * 60 * 1000L)
    val months = TrendAggregator.monthRange(MonthKey.of(YearMonth.of(2026, 11)), 2)

    val shanghaiTotals = TrendAggregator.bucket(listOf(expense(700, boundary)), months, shanghai)
    assertEquals("2026-11", shanghaiTotals[1].month.value)
    assertEquals(700L, shanghaiTotals[1].expenseMinor)
    assertEquals(0L, shanghaiTotals[0].expenseMinor)

    val utcTotals = TrendAggregator.bucket(listOf(expense(700, boundary)), months, ZoneId.of("UTC"))
    assertEquals("归入 10 月，说明 UTC 口径确实不同", 700L, utcTotals[0].expenseMinor)
  }

  @Test
  fun empty_input_yields_all_zero_totals() {
    val months = TrendAggregator.monthRange(MonthKey.of(YearMonth.of(2026, 10)), 3)
    val totals = TrendAggregator.bucket(emptyList(), months, shanghai)

    assertEquals(3, totals.size)
    assertEquals(0L, totals.sumOf { it.expenseMinor })
    assertEquals(0L, totals.sumOf { it.incomeMinor })
  }
}
