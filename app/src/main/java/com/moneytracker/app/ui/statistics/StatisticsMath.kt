package com.moneytracker.app.ui.statistics

import java.util.Locale

/**
 * 统计页的纯计算，便于直接单元测试。
 *
 * 百分比刻意用 [Locale.US] 格式化：部分区域的区域设置以逗号作小数分隔符，
 * 会往界面里塞进「42,1%」这种文案。
 */
object StatisticsMath {

  /** 各切片占比。总和为 0 时全部返回 0，避免除零。 */
  fun ratios(values: List<Long>): List<Float> {
    val total = values.sum()
    if (total <= 0L) return List(values.size) { 0f }
    return values.map { (it.toDouble() / total.toDouble()).toFloat() }
  }

  /** 百分比文案，保留一位小数，如 "42.1%"。 */
  fun percentText(part: Long, total: Long): String {
    if (total <= 0L) return "0.0%"
    val percent = part.toDouble() / total.toDouble() * 100.0
    return String.format(Locale.US, "%.1f%%", percent)
  }
}
