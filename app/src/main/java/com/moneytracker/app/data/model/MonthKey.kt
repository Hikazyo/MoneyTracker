package com.moneytracker.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 形如 "2026-10" 的月份键。
 *
 * 重要：月份归属一律按**本地时区**计算。若直接按 UTC 换算，晚上 8 点后（UTC+8 已是次日）
 * 记的账会被归进错误的月份——这是记账类应用最典型的隐蔽 bug。
 */
@JvmInline
value class MonthKey(val value: String) : Comparable<MonthKey> {

  fun toYearMonth(): YearMonth = YearMonth.parse(value)

  fun plusMonths(n: Long): MonthKey = MonthKey(toYearMonth().plusMonths(n).toString())

  /** 用于界面显示，如 "2026年10月" */
  fun displayName(): String {
    val ym = toYearMonth()
    return "${ym.year}年${ym.monthValue}月"
  }

  override fun compareTo(other: MonthKey): Int = value.compareTo(other.value)

  override fun toString(): String = value

  companion object {
    fun of(yearMonth: YearMonth): MonthKey = MonthKey(yearMonth.toString())

    /** 由时间戳（epoch millis）按指定时区求所属月份。 */
    fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): MonthKey =
      MonthKey(YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(zone)).toString())

    fun current(zone: ZoneId = ZoneId.systemDefault()): MonthKey = of(YearMonth.now(zone))
  }
}

/**
 * 该月起点（含）的 epoch millis。
 *
 * 用 atStartOfDay 而不是 atTime(0, 0)：前者能正确处理夏令时跳变（某些时区当天 00:00 不存在）。
 */
fun YearMonth.startMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
  atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

/** 下月起点（不含），即本月的开区间上界。用开区间可避免月末最后一毫秒的边界遗漏。 */
fun YearMonth.endExclusiveMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
  plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

fun LocalDate.startMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
  atStartOfDay(zone).toInstant().toEpochMilli()

fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
  Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** 把本地日期转为时间戳（当天 00:00）。 */
fun LocalDate.toEpochMillis(zone: ZoneId = ZoneId.systemDefault()): Long = startMillis(zone)
