package com.moneytracker.app.data.ocr

import com.moneytracker.app.data.model.Money

/**
 * 金额行的识别与排除规则。
 *
 * 这里的每条规则都是从真实识别输出里反推出来的，不是凭空设想的：
 *  - 银行的 `-¥14.98` 会被识别成 `一¥14.98`（负号变汉字「一」），所以符号集合必须放宽；
 *  - 「余额」列本身也会被识别错，实测出现过 `余額` / `余颌` / `余颤`，所以不能只匹配「余额」二字。
 */
object AmountRules {

  /**
   * 金额行必须是**整行只有一个金额**。
   *
   * 用它来排除 `支出¥1466.71 收入¥615.90` 这种页头汇总行：
   * 那行不是单笔金额，误当成账目会凭空多出一笔。
   */
  private val AMOUNT_LINE = Regex("""^([+\-−—一])?\s*[¥￥]?\s*([0-9][0-9,]*\.[0-9]{2})$""")

  /** 金额行中用于判断「正负号是否明确」的那个字符。 */
  private const val MINUS_LIKE = "−—-一"

  data class Parsed(
    /** 该行是否带负号（含被识别成汉字「一」的情况）。 */
    val negative: Boolean,
    /** 是否**显式**写了符号。没有符号的金额需要按来源推断方向，并在预览里标记待确认。 */
    val explicitSign: Boolean,
    val minor: Long,
  )

  /** 解析一行金额；不是金额行则返回 null。 */
  fun parse(text: String): Parsed? {
    val match = AMOUNT_LINE.matchEntire(text.trim()) ?: return null
    val sign = match.groupValues[1]
    val number = match.groupValues[2].replace(",", "")
    val minor = Money.parse(number) ?: return null
    if (minor <= 0L) return null
    return Parsed(
      negative = sign.isNotEmpty() && MINUS_LIKE.contains(sign[0]),
      explicitSign = sign.isNotEmpty(),
      minor = minor,
    )
  }

  /**
   * 是否是「余额」行。
   *
   * 判据不是精确文本：只要这行同时出现「余」和货币符号/冒号，就按余额处理。
   * 银行流水里余额与交易金额**右对齐程度接近**，靠位置区分不可靠，必须靠内容。
   */
  fun isBalanceLine(text: String): Boolean {
    if (!text.contains('余')) return false
    return text.contains('¥') ||
      text.contains('￥') ||
      text.contains(':') ||
      text.contains('：')
  }

  /** 页头汇总行：`支出¥1466.71 收入¥615.90`。 */
  fun isSummaryLine(text: String): Boolean {
    val hasMoney = text.contains('¥') || text.contains('￥')
    return hasMoney && text.contains("支出") && text.contains("收入")
  }

  /** 既不是余额行也不是汇总行，才可能是真的单笔金额。 */
  fun isUsableAmountLine(text: String): Boolean =
    !isBalanceLine(text) && !isSummaryLine(text)
}
