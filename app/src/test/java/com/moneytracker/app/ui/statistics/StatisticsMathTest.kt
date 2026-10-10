package com.moneytracker.app.ui.statistics

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsMathTest {

  @Test
  fun ratios_are_proportional_to_amounts() {
    val ratios = StatisticsMath.ratios(listOf(50L, 30L, 20L))
    assertEquals(3, ratios.size)
    assertEquals(0.5f, ratios[0], 1e-6f)
    assertEquals(0.3f, ratios[1], 1e-6f)
    assertEquals(0.2f, ratios[2], 1e-6f)
  }

  @Test
  fun ratios_sum_to_one() {
    val ratios = StatisticsMath.ratios(listOf(1234L, 5678L, 9012L, 345L))
    assertEquals(1f, ratios.sum(), 1e-5f)
  }

  @Test
  fun ratios_of_zero_total_are_all_zero_instead_of_dividing_by_zero() {
    val ratios = StatisticsMath.ratios(listOf(0L, 0L, 0L))
    assertEquals(listOf(0f, 0f, 0f), ratios)
  }

  @Test
  fun ratios_of_empty_list_is_empty() {
    assertEquals(emptyList<Float>(), StatisticsMath.ratios(emptyList()))
  }

  @Test
  fun ratios_of_single_value_is_one() {
    val ratios = StatisticsMath.ratios(listOf(777L))
    assertEquals(1f, ratios.single(), 1e-6f)
  }

  @Test
  fun percent_text_keeps_one_decimal() {
    assertEquals("50.0%", StatisticsMath.percentText(50, 100))
    assertEquals("42.1%", StatisticsMath.percentText(421, 1000))
    assertEquals("0.1%", StatisticsMath.percentText(1, 1000))
    assertEquals("100.0%", StatisticsMath.percentText(100, 100))
  }

  @Test
  fun percent_text_handles_zero_total() {
    assertEquals("0.0%", StatisticsMath.percentText(0, 0))
  }

  @Test
  fun percent_text_always_uses_dot_as_decimal_separator() {
    // 德语等区域的区域设置用逗号作小数分隔符，会往界面里塞进「42,1%」。
    // 这里固定用 Locale.US 就是为了挡住这种情况。
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.GERMANY)
      val text = StatisticsMath.percentText(421, 1000)
      assertEquals("42.1%", text)
      assertTrue("不能出现逗号小数分隔符", !text.contains(','))
    } finally {
      Locale.setDefault(original)
    }
  }
}
