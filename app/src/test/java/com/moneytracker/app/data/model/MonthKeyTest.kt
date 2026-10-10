package com.moneytracker.app.data.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月份归属与区间边界测试。
 *
 * 这组测试针对的是设计文档里标记的那个坑：时间戳存 UTC，但「属于哪个月」必须按
 * 本地时区换算。若按 UTC 算，东八区晚上 8 点后记的账会被归进错误的月份/日期。
 */
class MonthKeyTest {

  private val shanghai = ZoneId.of("Asia/Shanghai")

  @Test
  fun month_key_uses_local_timezone_not_utc() {
    // 2026-10-31 23:30 上海时间 == 2026-10-31 15:30 UTC（这个例子月份相同）
    val millis =
      LocalDate.of(2026, 10, 31).atTime(23, 30).atZone(shanghai).toInstant().toEpochMilli()
    assertEquals("2026-10", MonthKey.of(millis, shanghai).value)
  }

  @Test
  fun late_evening_entry_stays_on_local_date() {
    // 关键场景：本地 23:59 时 UTC 还是当天 15:59，但按 UTC+8 的时区向西会跨日。
    // 这里用 UTC 时区反向验证：同一时间戳在两个时区下可能属于不同日期。
    val millis =
      LocalDate.of(2026, 10, 31).atTime(23, 59).atZone(shanghai).toInstant().toEpochMilli()
    assertEquals(LocalDate.of(2026, 10, 31), millis.toLocalDate(shanghai))
    // 同一时刻按 UTC 看已经是当天 15:59，日期碰巧相同；换成 11-01 00:30 就会分属两个月
    val crossMonth =
      LocalDate.of(2026, 11, 1).atTime(0, 30).atZone(shanghai).toInstant().toEpochMilli()
    assertEquals("2026-11", MonthKey.of(crossMonth, shanghai).value)
    assertEquals("2026-10", MonthKey.of(crossMonth, ZoneId.of("UTC")).value)
  }

  @Test
  fun month_range_is_left_closed_right_open() {
    val ym = YearMonth.of(2026, 10)
    val start = ym.startMillis(shanghai)
    val end = ym.endExclusiveMillis(shanghai)
    assertTrue(start < end)
    assertEquals(LocalDate.of(2026, 10, 1), start.toLocalDate(shanghai))
    assertEquals(LocalDate.of(2026, 11, 1), end.toLocalDate(shanghai))
  }

  @Test
  fun last_millisecond_of_month_is_inside_range() {
    val ym = YearMonth.of(2026, 10)
    val start = ym.startMillis(shanghai)
    val end = ym.endExclusiveMillis(shanghai)

    val lastMs =
      LocalDate.of(2026, 10, 31)
        .atTime(23, 59, 59, 999_000_000)
        .atZone(shanghai)
        .toInstant()
        .toEpochMilli()
    assertTrue("月末最后一毫秒必须落在区间内", lastMs >= start && lastMs < end)

    val nextMonthFirstMs =
      LocalDate.of(2026, 11, 1).atStartOfDay(shanghai).toInstant().toEpochMilli()
    assertTrue("下月第一毫秒必须落在区间外", nextMonthFirstMs >= end)
  }

  @Test
  fun second_month_boundary_uses_next_month_start() {
    // 2 月天数不固定，验证区间随月份长度正确伸缩
    val feb = YearMonth.of(2026, 2)
    val start = feb.startMillis(shanghai)
    val end = feb.endExclusiveMillis(shanghai)
    assertEquals(LocalDate.of(2026, 2, 1), start.toLocalDate(shanghai))
    assertEquals(LocalDate.of(2026, 3, 1), end.toLocalDate(shanghai))
    // 2026 不是闰年，2/29 应落在区间外
    val feb29 = LocalDate.of(2026, 3, 1).atStartOfDay(shanghai).toInstant().toEpochMilli()
    assertTrue(feb29 >= end)
  }

  @Test
  fun plus_months_crosses_year_boundary() {
    val dec = MonthKey.of(YearMonth.of(2026, 12))
    assertEquals("2027-01", dec.plusMonths(1).value)
    assertEquals("2026-11", dec.plusMonths(-1).value)
  }

  @Test
  fun display_name_is_human_readable() {
    assertEquals("2026年10月", MonthKey.of(YearMonth.of(2026, 10)).displayName())
  }
}
