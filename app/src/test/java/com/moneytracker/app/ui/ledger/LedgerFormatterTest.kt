package com.moneytracker.app.ui.ledger

import com.moneytracker.app.data.model.MonthKey
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerFormatterTest {

  private val shanghai = ZoneId.of("Asia/Shanghai")

  @Test
  fun month_label_is_chinese() {
    assertEquals("2026年10月", LedgerFormatter.monthLabel(MonthKey.of(YearMonth.of(2026, 10))))
  }

  @Test
  fun today_and_yesterday_use_relative_labels() {
    val today = LocalDate.of(2026, 10, 10)
    assertEquals("今天", LedgerFormatter.dayLabel(today, today))
    assertEquals("昨天", LedgerFormatter.dayLabel(today.minusDays(1), today))
  }

  @Test
  fun yesterday_across_year_boundary_still_reads_yesterday() {
    // 跨年边界：1 月 1 日的昨天是上一年 12 月 31 日
    val today = LocalDate.of(2027, 1, 1)
    assertEquals("昨天", LedgerFormatter.dayLabel(LocalDate.of(2026, 12, 31), today))
  }

  @Test
  fun other_days_show_date_and_weekday() {
    val today = LocalDate.of(2026, 10, 10)
    // 2026-10-05 是周一
    assertEquals("10月5日 周一", LedgerFormatter.dayLabel(LocalDate.of(2026, 10, 5), today))
  }

  @Test
  fun weekday_mapping_covers_all_seven_days() {
    // 2026-10-05 是周一，连续七天
    val expected = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    expected.forEachIndexed { offset, label ->
      assertEquals(label, LedgerFormatter.weekdayZh(LocalDate.of(2026, 10, 5).plusDays(offset.toLong())))
    }
  }

  @Test
  fun signed_amount_marks_direction() {
    assertEquals("-12.34", LedgerFormatter.signedAmount(1234, isExpense = true))
    assertEquals("+12.34", LedgerFormatter.signedAmount(1234, isExpense = false))
  }

  @Test
  fun large_amounts_are_grouped_for_readability() {
    assertEquals("-1,234,567.89", LedgerFormatter.signedAmount(123456789, isExpense = true))
  }

  @Test
  fun day_summary_omits_directions_that_did_not_happen() {
    assertEquals("支出 12.34", LedgerFormatter.daySummary(expenseMinor = 1234, incomeMinor = 0))
    assertEquals("收入 50.00", LedgerFormatter.daySummary(expenseMinor = 0, incomeMinor = 5000))
    assertEquals("", LedgerFormatter.daySummary(expenseMinor = 0, incomeMinor = 0))
  }

  @Test
  fun day_summary_shows_both_when_both_happened() {
    val text = LedgerFormatter.daySummary(expenseMinor = 1234, incomeMinor = 5000)
    assertEquals("支出 12.34  收入 50.00", text)
  }

  @Test
  fun balance_prefixes_plus_only_for_positive() {
    assertEquals("+100.00", LedgerFormatter.balance(10000))
    assertEquals("0.00", LedgerFormatter.balance(0))
    assertEquals("-100.00", LedgerFormatter.balance(-10000))
  }

  @Test
  fun time_label_is_zero_padded() {
    val millis =
      LocalDate.of(2026, 10, 5)
        .atTime(9, 5)
        .atZone(shanghai)
        .toInstant()
        .toEpochMilli()
    assertEquals("09:05", LedgerFormatter.timeLabel(millis, shanghai))
  }

  @Test
  fun date_label_is_readable() {
    assertEquals("2026年10月5日", LedgerFormatter.dateLabel(LocalDate.of(2026, 10, 5)))
  }
}
