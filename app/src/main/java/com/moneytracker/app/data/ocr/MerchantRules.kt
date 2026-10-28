package com.moneytracker.app.data.ocr

/**
 * 商户名清洗。
 *
 * 账单列表最左边是一列圆形图标，OCR 会把图标也当成字符识别出来，**粘在商户名前面**。
 * 实测样本：`0秋夫`、`8瑞幸咖啡`、`C支付宝小荷包-自动攒`、`G企鹅共享`、`|储蓄卡3306 08:32`。
 *
 * ## 为什么只剥非汉字前缀
 *
 * 图标也可能被识别成汉字（`田南京邮电大学`、`體南京邮电大学`）。但汉字开头本身是合法的
 * 商户名（`田家米粉`），**没法从文本上区分「图标碰巧像汉字」和「名字就叫这个」**。
 * 剥错会破坏正确的名字，而留着只影响可读性、不影响金额与分类（关键字匹配用的是「包含」，
 * `8瑞幸咖啡` 里同样能匹配到 `咖啡`）。所以这里刻意保守：**只剥非汉字前缀**。
 */
object MerchantRules {

  /** 可能出现在行首的图标残留：竖线、方括号等。 */
  private const val LEADING_SYMBOLS = "|丨!！·•●○[]【】()（）"

  /**
   * 清洗一行商户文本。返回值可能为空串——调用方应把空串当作「没有商户」。
   */
  fun clean(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) return s

    // 1. 反复剥掉行首的标点/符号
    while (s.isNotEmpty() && LEADING_SYMBOLS.contains(s[0])) {
      s = s.substring(1).trim()
    }
    if (s.isEmpty()) return s

    // 2. 单个 ASCII 数字/字母 + 紧跟汉字：几乎可以确定是图标被识别成了字符
    if (s.length > 1 && (s[0].isDigit() || s[0] in 'A'..'Z' || s[0] in 'a'..'z')) {
      val rest = s.substring(1)
      if (rest.isNotEmpty() && isCjkOrWide(rest[0])) {
        return rest.trim()
      }
    }

    return s
  }

  private fun isCjkOrWide(c: Char): Boolean =
    c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uFF00'..'\uFFEF'
}
