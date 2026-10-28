package com.moneytracker.app.data.ocr

/**
 * 支付宝账单行内的分类标签 → 内置分类的 `iconKey`。
 *
 * 这是截图识别相对 CSV 导入的一个额外好处：支付宝已经把分类写在界面上了
 * （`餐飲美食` / `教育培訓` / `生活服務`），直接用它比任何关键词猜测都准。
 *
 * 标签有简繁两种写法，都要认（实测截图里是繁体）。
 */
object AlipayCategoryMap {

  private val LABEL_TO_ICON_KEY: Map<String, String> =
    mapOf(
      "餐饮美食" to "food",
      "餐飲美食" to "food",
      "教育培训" to "education",
      "教育培訓" to "education",
      "生活服务" to "other_expense",
      "生活服務" to "other_expense",
      "交通出行" to "transport",
      "购物" to "shopping",
      "購物" to "shopping",
      "医疗健康" to "medical",
      "醫療健康" to "medical",
      "通讯" to "communication",
      "通訊" to "communication",
      "数码" to "digital",
      "數碼" to "digital",
      "服饰" to "clothes",
      "服飾" to "clothes",
      "美容" to "beauty",
      "美妆" to "beauty",
      "美妝" to "beauty",
      "娱乐" to "entertainment",
      "娛樂" to "entertainment",
      "旅行" to "travel",
      "运动" to "sport",
      "運動" to "sport",
    )

  /**
   * 资金划转类的标签。它们不是消费分类，**不给 iconKey**，
   * 而是让 [SuspiciousRules] 把那行标成「疑似非消费」。
   */
  private val TRANSFER_LABELS = setOf("账户存取", "賬戶存取", "脹戶存取")

  /** 该文本是否是支付宝的分类标签（用于在行内定位「中间那行」）。 */
  fun isLabel(text: String): Boolean {
    val s = text.trim()
    return LABEL_TO_ICON_KEY.containsKey(s) || TRANSFER_LABELS.contains(s)
  }

  fun isTransferLabel(text: String): Boolean = TRANSFER_LABELS.contains(text.trim())

  /** 标签对应的分类 iconKey；划转类或未知标签返回 null。 */
  fun iconKeyFor(text: String): String? = LABEL_TO_ICON_KEY[text.trim()]
}
