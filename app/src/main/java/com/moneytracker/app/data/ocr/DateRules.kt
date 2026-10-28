package com.moneytracker.app.data.ocr

import java.time.LocalDate

/**
 * 日期相关的识别规则。
 *
 * 截图里的日期有四种长相，而且**没有统一格式**（同一天不同平台写法完全不同）：
 *  - 微信：`10月24日 19:32`
 *  - 支付宝：`10-26 22:16`，更早的用 `10-25 09:22`
 *  - 银行：行内只有 `储蓄卡3306 11:47`，日期在外面按 `今天 / 昨天 / 10.26` 分组
 *  - 相对日期：`昨天 09:22` —— 实测会被识别成 `I# 09:22`，**不可靠**
 *
 * 因此这里只负责「从一行文本里尽可能抽取」，年份与兜底由 [ScreenshotParser] 决定。
 */
object DateRules {

  private val FULL_DATE = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*日""")
  private val DASH_DATE = Regex("""(?<!\d)(\d{1,2})-(\d{1,2})(?!\d)""")
  private val MONTH_DOT_DAY = Regex("""^(\d{1,2})\.(\d{1,2})$""")
  private val TIME = Regex("""(\d{2})[:：](\d{2})""")
  private val HEADER_YEAR_MONTH = Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月""")
  private val HEADER_YEAR_DOT = Regex("""(\d{4})\.(\d{1,2})""")
  private val FILE_DATE = Regex("""(\d{4})(\d{2})(\d{2})""")

  /** 页头里的「年 + 月」，是推断年份最可靠的来源（微信 `2026年10月`、银行 `2026.10`）。 */
  data class YearMonth(val year: Int, val month: Int)

  /** 一组分组头，用于给「只有时间没有日期」的行补日期。 */
  sealed interface GroupHeader {
    data class Absolute(val month: Int, val day: Int) : GroupHeader

    data class Relative(val relative: RelativeDay) : GroupHeader
  }

  enum class RelativeDay { TODAY, YESTERDAY }

  /** 行内的「月日」（`10月24日` 或 `10-26`）。 */
  fun findMonthDay(text: String): Pair<Int, Int>? {
    FULL_DATE.find(text)?.let { m ->
      val month = m.groupValues[1].toIntOrNull() ?: return@let
      val day = m.groupValues[2].toIntOrNull() ?: return@let
      if (isValidMonthDay(month, day)) return month to day
    }
    // `10-26` 要避开 `2026-10` 这类年份开头；前面的 (?<!\d) 已经挡掉
    DASH_DATE.find(text)?.let { m ->
      val month = m.groupValues[1].toIntOrNull() ?: return@let
      val day = m.groupValues[2].toIntOrNull() ?: return@let
      if (isValidMonthDay(month, day)) return month to day
    }
    return null
  }

  /**
   * 行内的时间（`19:32`）。
   *
   * 单独取时间，是因为银行流水把时间跟卡号写在了一起，而 OCR 会把它们**粘成一个词**：
   * `储蓄卡330611:47`（实测如此，没有空格）。所以这里不能用「前面不能是数字」的断言——
   * 那样整个时间都会被卡号挡住；改为取**最后一个** `HH:MM`。
   */
  fun findTime(text: String): Pair<Int, Int>? {
    val matches = TIME.findAll(text).toList()
    for (m in matches.asReversed()) {
      val hour = m.groupValues[1].toIntOrNull() ?: continue
      val minute = m.groupValues[2].toIntOrNull() ?: continue
      if (hour in 0..23 && minute in 0..59) return hour to minute
    }
    return null
  }

  /** 分组头：`今天` / `昨天` / `10.26`。 */
  fun parseGroupHeader(text: String): GroupHeader? {
    val s = text.trim()
    when (s) {
      "今天" -> return GroupHeader.Relative(RelativeDay.TODAY)
      "昨天" -> return GroupHeader.Relative(RelativeDay.YESTERDAY)
    }
    val m = MONTH_DOT_DAY.matchEntire(s) ?: return null
    val month = m.groupValues[1].toIntOrNull() ?: return null
    val day = m.groupValues[2].toIntOrNull() ?: return null
    if (!isValidMonthDay(month, day)) return null
    return GroupHeader.Absolute(month, day)
  }

  /** 页头的「年月」。支付宝的页头只有「10月」，没有年份，所以这里允许返回 null。 */
  fun findYearMonth(text: String): YearMonth? {
    HEADER_YEAR_MONTH.find(text)?.let { m ->
      val year = m.groupValues[1].toIntOrNull() ?: return@let
      val month = m.groupValues[2].toIntOrNull() ?: return@let
      if (month in 1..12) return YearMonth(year, month)
    }
    HEADER_YEAR_DOT.find(text)?.let { m ->
      val year = m.groupValues[1].toIntOrNull() ?: return@let
      val month = m.groupValues[2].toIntOrNull() ?: return@let
      if (month in 1..12) return YearMonth(year, month)
    }
    return null
  }

  /**
   * 从截图文件名里取日期。
   *
   * 系统截图默认叫 `Screenshot_20261028_153409.jpg`，这是比「导入当天」可靠得多的参考，
   * 因为用户常常过几天才想起来导入。
   */
  fun parseFileDate(fileName: String?): LocalDate? {
    if (fileName.isNullOrBlank()) return null
    val m = FILE_DATE.find(fileName) ?: return null
    val year = m.groupValues[1].toIntOrNull() ?: return null
    val month = m.groupValues[2].toIntOrNull() ?: return null
    val day = m.groupValues[3].toIntOrNull() ?: return null
    if (!isValidMonthDay(month, day)) return null
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
  }

  /**
   * 推断年份。
   *
   * 优先用页头给的年份；拿不到时用参考日期，并且**跨年时往回退一年**——
   * 1 月导入 12 月的截图，那次消费属于去年。
   */
  fun resolveYear(header: YearMonth?, rowMonth: Int, reference: LocalDate): Int =
    header?.year ?: if (rowMonth > reference.monthValue) reference.year - 1 else reference.year

  private fun isValidMonthDay(month: Int, day: Int): Boolean =
    month in 1..12 && day in 1..31
}
