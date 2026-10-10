package com.moneytracker.app.data.model

import java.time.ZoneId

/** 趋势统计只需要金额、方向、时间三个字段。 */
data class AmountAt(val amountMinor: Long, val type: TransactionType, val occurredAt: Long)

/** 某个月的收支合计。 */
data class MonthTotal(val month: MonthKey, val expenseMinor: Long, val incomeMinor: Long)

/**
 * 把账目按**本地时区**的月份分桶。
 *
 * 为什么在 Kotlin 侧分桶，而不是用 SQL 的 `strftime(..., 'localtime')`：
 * 后者依赖 SQLite 对宿主时区的解释，行为不够可控；在 Kotlin 侧分桶可以直接单元测试，
 * 并且复用与列表页完全相同的时区逻辑（[MonthKey.of]）——两处口径一致，不会出现
 * 「列表里算这个月、图表里算上个月」这种难查的对不上账。
 */
object TrendAggregator {

  /**
   * @param months 需要输出的月份，顺序即输出顺序；没有账目的月份也会返回一条零值记录，
   *   这样柱状图不会因为缺月而错位。
   */
  fun bucket(entries: List<AmountAt>, months: List<MonthKey>, zone: ZoneId): List<MonthTotal> {
    val grouped = entries.groupBy { MonthKey.of(it.occurredAt, zone) }
    return months.map { month ->
      val items = grouped[month].orEmpty()
      MonthTotal(
        month = month,
        expenseMinor =
          items.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountMinor },
        incomeMinor = items.filter { it.type == TransactionType.INCOME }.sumOf { it.amountMinor },
      )
    }
  }

  /** 生成截至 [endMonth]（含）的连续 [count] 个月，按时间正序。 */
  fun monthRange(endMonth: MonthKey, count: Int): List<MonthKey> {
    require(count > 0) { "count 必须为正数" }
    val end = endMonth.toYearMonth()
    val start = end.minusMonths((count - 1).toLong())
    return (0 until count).map { MonthKey.of(start.plusMonths(it.toLong())) }
  }
}
