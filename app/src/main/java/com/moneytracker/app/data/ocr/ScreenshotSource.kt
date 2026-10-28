package com.moneytracker.app.data.ocr

/**
 * 截图来自哪个平台。
 *
 * 判定靠**版式特征词**，而不是靠用户选择——用户手里常常是别人转发来的截图，
 * 自己都说不清是哪家的。识别不出来时降级为 [UNKNOWN]，由解析器按通用规则尽力而为，
 * 而不是直接报错（见 DESIGN.md §十二）。
 */
enum class ScreenshotSource(val displayName: String) {
  WECHAT("微信账单"),
  ALIPAY("支付宝账单"),
  BANK("银行流水"),
  UNKNOWN("未知版式"),
  ;

  companion object {

    /** 判定顺序有讲究：银行流水的「储蓄卡/余额」最独特，先判它，避免被通用词抢走。 */
    fun detect(lines: List<OcrLine>): ScreenshotSource {
      val all = lines.joinToString("\n") { it.text }
      return when {
        all.contains("储蓄卡") ||
          all.contains("银行卡") ||
          all.contains("余额") ||
          all.contains("余額") -> BANK

        all.contains("全部账单") ||
          all.contains("查找交易") ||
          all.contains("收支统计") -> WECHAT

        all.contains("收支分析") ||
          all.contains("轉賬") ||
          all.contains("转账") ||
          all.contains("訂單") ||
          all.contains("賬戶存取") ||
          all.contains("账户存取") -> ALIPAY

        else -> UNKNOWN
      }
    }
  }
}
