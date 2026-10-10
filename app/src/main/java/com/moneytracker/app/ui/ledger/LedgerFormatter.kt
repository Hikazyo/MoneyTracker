package com.moneytracker.app.ui.ledger

import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.model.MonthKey
import java.time.LocalDate
import java.time.ZoneId

/**
 * 列表页的文案格式化。
 *
 * 全部是纯函数并显式接收「今天」与「时区」，因此不依赖系统时钟即可测试——
 * 「今天/昨天」这类相对文案最容易因为边界（跨月、跨年）出错。
 */
object LedgerFormatter {

  fun monthLabel(month: MonthKey): String = month.displayName()

  fun weekdayZh(date: LocalDate): String =
    when (date.dayOfWeek.value) {
      1 -> "周一"
      2 -> "周二"
      3 -> "周三"
      4 -> "周四"
      5 -> "周五"
      6 -> "周六"
      else -> "周日"
    }

  /** 日期小标题：今天 / 昨天 / "10月5日 周一"。 */
  fun dayLabel(date: LocalDate, today: LocalDate): String =
    when (date) {
      today -> "今天"
      today.minusDays(1) -> "昨天"
      else -> "${date.monthValue}月${date.dayOfMonth}日 ${weekdayZh(date)}"
    }

  /** 时间 "14:30"。 */
  fun timeLabel(epochMillis: Long, zone: ZoneId): String {
    val time = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
  }

  /** 金额前带方向符号：支出 "-12.34"，收入 "+12.34"。 */
  fun signedAmount(minor: Long, isExpense: Boolean): String =
    if (isExpense) "-${Money.formatGrouped(minor)}" else "+${Money.formatGrouped(minor)}"

  /**
   * 某一天的小计文案。
   *
   * 只显示当天实际发生过的方向，避免出现「支出 ¥0.00」这种噪音。
   */
  fun daySummary(expenseMinor: Long, incomeMinor: Long): String {
    val parts = buildList {
      if (expenseMinor > 0) add("支出 ${Money.formatGrouped(expenseMinor)}")
      if (incomeMinor > 0) add("收入 ${Money.formatGrouped(incomeMinor)}")
    }
    return parts.joinToString("  ")
  }

  /** 结余：正数显示 "+"，负数自带 "-"。 */
  fun balance(balanceMinor: Long): String {
    val sign = if (balanceMinor > 0) "+" else ""
    return "$sign${Money.formatGrouped(balanceMinor)}"
  }

  /** 日期选择器用：把 UTC 毫秒（DatePicker 返回值的语义）转成本地日期。 */
  fun dateLabel(date: LocalDate): String = "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
}
