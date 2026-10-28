package com.moneytracker.app.data.ocr

/**
 * 「疑似非消费」判定。
 *
 * 起因是一个真实的记账错误风险：`支付宝小荷包-自动攒 −1.00` 是**把钱从一个口袋挪到另一个口袋**，
 * 不是花销。按金额正负号无脑记成支出，会实打实地虚增当月支出，而且事后完全看不出异常——
 * 它长得跟一笔正常的一元消费一模一样。
 *
 * 所以这里只做**标记**，不替用户做决定：命中关键词的行在预览里默认不勾选，
 * 用户看一眼就知道这是资金划转，可以手动勾掉；如果它确实是一笔消费，也能勾回来。
 *
 * **绝不静默丢弃**——静默丢弃等于应用替用户隐瞒了一笔流水。
 */
object SuspiciousRules {

  /** 命中任一关键词即标记为「疑似非消费」。全部取自真实账单里反复出现的资金划转类目。 */
  private val KEYWORDS =
    listOf(
      "自动攒",
      "小荷包",
      "零钱通",
      "余额宝",
      "转入",
      "转出",
      "转账",
      "还款",
      "退款",
      "退回",
      "红包",
      "账户存取",
      "賬戶存取",
      "提现",
      "儲值",
    )

  fun isSuspicious(vararg texts: String?): Boolean {
    val haystack = texts.filterNotNull().joinToString(" ")
    if (haystack.isBlank()) return false
    return KEYWORDS.any { haystack.contains(it) }
  }
}
