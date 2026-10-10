package com.moneytracker.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 金额换算测试。
 *
 * 金额是记账应用最核心也最容易出错的部分，这里覆盖补零、负数、非法输入、
 * 以及 parse/format 往返一致性。
 */
class MoneyTest {

  @Test
  fun format_pads_cents_to_two_digits() {
    assertEquals("0.00", Money.format(0))
    assertEquals("0.05", Money.format(5))
    assertEquals("0.50", Money.format(50))
    assertEquals("1.00", Money.format(100))
    assertEquals("12.34", Money.format(1234))
    assertEquals("100.00", Money.format(10000))
  }

  @Test
  fun format_handles_negative_values() {
    assertEquals("-0.05", Money.format(-5))
    assertEquals("-12.34", Money.format(-1234))
  }

  @Test
  fun parse_accepts_common_user_input() {
    assertEquals(0L, Money.parse("0"))
    assertEquals(1200L, Money.parse("12"))
    assertEquals(1230L, Money.parse("12.3"))
    assertEquals(1234L, Money.parse("12.34"))
    assertEquals(50L, Money.parse(".5"))
    assertEquals(1200L, Money.parse("12."))
    assertEquals(5L, Money.parse("0.05"))
    assertEquals(1234L, Money.parse("  12.34  "))
  }

  @Test
  fun parse_rejects_invalid_input() {
    assertNull(Money.parse(""))
    assertNull(Money.parse("."))
    assertNull(Money.parse("1.2.3"))
    assertNull(Money.parse("1.234"))
    assertNull(Money.parse("-1"))
    assertNull(Money.parse("-1.00"))
    assertNull(Money.parse("abc"))
    assertNull(Money.parse("1a"))
    assertNull(Money.parse("1 000"))
  }

  @Test
  fun parse_rejects_more_than_nine_integer_digits() {
    // 上限 999,999,999.99
    assertEquals(99_999_999_999L, Money.parse("999999999.99"))
    assertNull(Money.parse("1000000000"))
  }

  @Test
  fun format_and_parse_round_trip() {
    val samples = listOf(0L, 1L, 9L, 10L, 99L, 100L, 1234L, 999_999L, 99_999_999_999L)
    samples.forEach { minor -> assertEquals(minor, Money.parse(Money.format(minor))) }
  }

  @Test
  fun format_with_symbol_prefixes_yuan_sign() {
    assertEquals("¥12.34", Money.formatWithSymbol(1234))
    assertEquals("¥0.00", Money.formatWithSymbol(0))
  }

  @Test
  fun format_grouped_inserts_thousand_separators() {
    assertEquals("0.00", Money.formatGrouped(0))
    assertEquals("1.00", Money.formatGrouped(100))
    assertEquals("999.99", Money.formatGrouped(99999))
    assertEquals("1,000.00", Money.formatGrouped(100000))
    assertEquals("12,345.67", Money.formatGrouped(1234567))
    assertEquals("1,234,567.89", Money.formatGrouped(123456789))
    assertEquals("999,999,999.99", Money.formatGrouped(Money.MAX_MINOR))
  }

  @Test
  fun format_grouped_handles_negative_values() {
    assertEquals("-1,234.56", Money.formatGrouped(-123456))
  }

  @Test
  fun format_itself_must_stay_parseable_so_it_never_gains_separators() {
    // 千分位只用于展示：format 的结果必须能被 parse 原样读回（输入回显、CSV 导出都依赖这一点）
    assertEquals("1234567.89", Money.format(123456789))
    assertEquals(123456789L, Money.parse(Money.format(123456789)))
  }

  @Test
  fun max_minor_is_consistent_with_format_and_parse() {
    // 常量与解析上限必须一致，否则会出现「能格式化但解析不回来」的金额
    assertEquals("999999999.99", Money.format(Money.MAX_MINOR))
    assertEquals(Money.MAX_MINOR, Money.parse(Money.format(Money.MAX_MINOR)))
  }
}
