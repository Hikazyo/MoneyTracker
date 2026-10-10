package com.moneytracker.app.data.importer

import com.moneytracker.app.data.model.TransactionType

/** 识别出的账单来源。 */
enum class BillFormat(val displayName: String) {
  WECHAT("微信支付账单"),
  ALIPAY("支付宝账单"),
  GENERIC("通用 CSV"),
}

/**
 * 解析出的一条待导入账目。
 *
 * 只承载「账单里写了什么」，不包含最终分类——分类要等界面拿到用户的分类表之后才能定。
 */
data class ImportedEntry(
  /** 去重键。优先用平台交易单号，没有则用「时间+金额+方向+对方」的组合。 */
  val sourceId: String,
  val occurredAt: Long,
  val type: TransactionType,
  val amountMinor: Long,
  val counterparty: String?,
  val product: String?,
  val note: String?,
  /** 由关键字规则猜出的分类 iconKey；null 表示没匹配上，归入用户指定的兜底分类。 */
  val suggestedIconKey: String?,
)

sealed interface ImportParseResult {

  data class Ok(
    val format: BillFormat,
    val entries: List<ImportedEntry>,
    /** 因方向为「不计收支」、状态为关闭/失败、或字段残缺而跳过的行数。 */
    val skippedRows: Int,
  ) : ImportParseResult {
    val unmatchedCount: Int get() = entries.count { it.suggestedIconKey == null }
  }

  /** 无法识别的文件。带上原因，让用户知道该怎么办。 */
  data class Unrecognized(val reason: String) : ImportParseResult
}
