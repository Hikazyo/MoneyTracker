package com.moneytracker.app.ui.edit

/**
 * 自定义数字键盘的输入规则。
 *
 * 抽成纯函数是有意的：金额输入的边界情况（前导零、多个小数点、小数位上限、
 * 整数位上限）最容易出错，放在纯函数里可以直接单元测试，不用起界面。
 */
object AmountInput {

  const val MAX_INTEGER_DIGITS = 9
  const val MAX_DECIMAL_DIGITS = 2

  /** 追加一个数字。 */
  fun appendDigit(current: String, digit: Char): String {
    if (digit !in '0'..'9') return current

    val dot = current.indexOf('.')
    if (dot >= 0) {
      // 已有小数点：限制小数位数
      if (current.length - dot - 1 >= MAX_DECIMAL_DIGITS) return current
    } else {
      // 无小数点：限制整数位数
      if (current.length >= MAX_INTEGER_DIGITS) return current
      // 避免出现 "00" 这种前导零
      if (current == "0" && digit == '0') return current
      if (current == "0") return digit.toString()
    }
    return current + digit
  }

  /** 追加小数点。重复按无效；空输入时补成 "0." 以便直接输入 ".5"。 */
  fun appendDot(current: String): String {
    if (current.contains('.')) return current
    if (current.isEmpty()) return "0."
    return "$current."
  }

  fun backspace(current: String): String = if (current.isEmpty()) "" else current.dropLast(1)

  fun clear(): String = ""

  /** 界面展示用：空输入显示 "0.00"，否则补全为两位小数。 */
  fun display(current: String): String {
    if (current.isEmpty()) return "0.00"
    val dot = current.indexOf('.')
    if (dot < 0) return "$current.00"
    val decimals = current.length - dot - 1
    return when {
      decimals == 0 -> current + "00"
      decimals == 1 -> current + "0"
      else -> current
    }
  }
}
