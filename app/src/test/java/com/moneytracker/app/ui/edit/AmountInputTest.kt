package com.moneytracker.app.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 数字键盘输入规则测试。
 *
 * 这些边界（前导零、多个小数点、位数上限）靠手点是很难覆盖全的，
 * 但一旦出错就是「金额存错」这种直接毁数据的后果。
 */
class AmountInputTest {

  @Test
  fun appending_digits_builds_the_number() {
    var input = ""
    listOf('1', '2').forEach { input = AmountInput.appendDigit(input, it) }
    assertEquals("12", input)
  }

  @Test
  fun leading_zero_is_replaced_by_next_digit() {
    var input = AmountInput.appendDigit("", '0')
    assertEquals("0", input)
    input = AmountInput.appendDigit(input, '5')
    assertEquals("5", input)
  }

  @Test
  fun repeated_zero_does_not_produce_double_zero() {
    var input = AmountInput.appendDigit("", '0')
    input = AmountInput.appendDigit(input, '0')
    assertEquals("0", input)
  }

  @Test
  fun integer_part_is_capped_at_nine_digits() {
    var input = ""
    repeat(12) { input = AmountInput.appendDigit(input, '9') }
    assertEquals("999999999", input)
  }

  @Test
  fun decimal_part_is_capped_at_two_digits() {
    var input = "1"
    input = AmountInput.appendDot(input)
    listOf('2', '3', '4').forEach { input = AmountInput.appendDigit(input, it) }
    assertEquals("1.23", input)
  }

  @Test
  fun second_dot_is_ignored() {
    var input = AmountInput.appendDot("1")
    input = AmountInput.appendDot(input)
    assertEquals("1.", input)
  }

  @Test
  fun dot_on_empty_input_becomes_zero_dot() {
    assertEquals("0.", AmountInput.appendDot(""))
  }

  @Test
  fun backspace_removes_last_character() {
    assertEquals("12.3", AmountInput.backspace("12.34"))
  }

  @Test
  fun backspace_on_empty_stays_empty() {
    assertEquals("", AmountInput.backspace(""))
  }

  @Test
  fun clear_resets_input() {
    assertEquals("", AmountInput.clear())
  }

  @Test
  fun display_pads_to_two_decimals() {
    assertEquals("0.00", AmountInput.display(""))
    assertEquals("12.00", AmountInput.display("12"))
    assertEquals("12.30", AmountInput.display("12.3"))
    assertEquals("12.34", AmountInput.display("12.34"))
    assertEquals("0.50", AmountInput.display("0.5"))
  }

  @Test
  fun realistic_key_sequence_produces_expected_amount() {
    var input = ""
    listOf('1', '2').forEach { input = AmountInput.appendDigit(input, it) }
    input = AmountInput.appendDot(input)
    listOf('3', '4').forEach { input = AmountInput.appendDigit(input, it) }

    assertEquals("12.34", input)
    assertEquals(1234L, com.moneytracker.app.data.model.Money.parse(input))
  }

  @Test
  fun sequence_with_leading_dot_parses_correctly() {
    var input = AmountInput.appendDot("")
    input = AmountInput.appendDigit(input, '5')
    assertEquals("0.5", input)
    assertEquals(50L, com.moneytracker.app.data.model.Money.parse(input))
  }
}
