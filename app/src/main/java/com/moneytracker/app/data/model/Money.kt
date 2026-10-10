package com.moneytracker.app.data.model

/**
 * 金额的换算与格式化。
 *
 * 全项目金额统一以「分」为单位的 Long 存储和运算，绝不使用 Double/Float：
 * 浮点无法精确表示小数（0.1 + 0.2 != 0.3），累加几千笔账后误差会实际显现。
 */
object Money {

  /** 单笔金额上限，约 10 亿元。同时用于挡住整数溢出与误输入。 */
  const val MAX_MINOR: Long = 99_999_999_999L

  /** 元部分允许的最大位数，超过即拒绝。 */
  private const val MAX_YUAN_DIGITS = 9

  /** 把「分」格式化为两位小数的元字符串：1234 -> "12.34"，-5 -> "-0.05"。 */
  fun format(minor: Long): String {
    val negative = minor < 0
    val abs = if (negative) -minor else minor
    val yuan = abs / 100
    val cents = abs % 100
    return buildString {
      if (negative) append('-')
      append(yuan)
      append('.')
      if (cents < 10) append('0')
      append(cents)
    }
  }

  /** 带货币符号：1234 -> "¥12.34" */
  fun formatWithSymbol(minor: Long): String = "¥" + format(minor)

  /**
   * 带千分位的展示格式：123456789 -> "1,234,567.89"。
   *
   * 与 [format] 分开是刻意的：[format] 的结果必须能被 [parse] 原样读回
   * （用于输入回显与 CSV 导出），加了逗号就破坏了往返一致性。
   * 千分位只用于界面展示。
   */
  fun formatGrouped(minor: Long): String {
    val plain = format(minor)
    val negative = plain.startsWith('-')
    val body = if (negative) plain.substring(1) else plain
    val dot = body.indexOf('.')
    val integerPart = body.substring(0, dot)
    val rest = body.substring(dot)
    val grouped = integerPart.reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-" else "") + grouped + rest
  }

  /**
   * 解析用户输入为「分」，非法输入返回 null。
   *
   * 接受 "12" / "12.3" / "12.34" / ".5" / "12."；
   * 拒绝空串、负号、多个小数点、超过 2 位小数、非数字、超过 9 位整数部分。
   */
  fun parse(input: String): Long? {
    val s = input.trim()
    if (s.isEmpty()) return null

    val dot = s.indexOf('.')
    if (dot != s.lastIndexOf('.')) return null

    val yuanPart = if (dot >= 0) s.substring(0, dot) else s
    val centPart = if (dot >= 0) s.substring(dot + 1) else ""

    // "." 或 "" 这类没有有效数字的输入
    if (yuanPart.isEmpty() && centPart.isEmpty()) return null
    if (yuanPart.any { !it.isDigit() }) return null
    if (centPart.any { !it.isDigit() }) return null
    if (centPart.length > 2) return null
    if (yuanPart.length > MAX_YUAN_DIGITS) return null

    val yuan = if (yuanPart.isEmpty()) 0L else yuanPart.toLong()
    // "".padEnd(2,'0') == "00"，所以 "12." 这类输入小数部分按 0 处理
    val cents = centPart.padEnd(2, '0').toLong()
    val minor = yuan * 100 + cents
    return if (minor > MAX_MINOR) null else minor
  }
}
