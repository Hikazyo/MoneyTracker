package com.moneytracker.app.data.importer

import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.model.TransactionType
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * 微信 / 支付宝账单导入（CSV 与 Excel 的 .xlsx 都支持）。
 *
 * 两个平台的文件有几个共同的现实问题，实现时必须处理：
 *
 *  1. **前面有十几行说明文字**，表头不在第一行 → 先扫描定位表头
 *  2. **括号全角半角不一致**（微信 `金额(元)`、支付宝 `金额（元）`）→ 归一化表头
 *  3. **日期没有空格分隔**（支付宝会出现 `2026-01-0512:30:00`）→ 用正则容忍
 *  4. **编码不同**（微信 UTF-8、支付宝 GBK）→ 见 [TextDecoding]
 *  5. **列顺序会变** → 按列名匹配，绝不按下标取值
 *
 * 而 **xlsx 有一类 CSV 没有的问题**：单元格是**有类型**的。
 * 日期可能是 Excel 的日期序列号（`45352.520833`），金额可能是数值而不是
 * `¥23.00` 这样的文本。因此表格来源要多做一步「单元格值归一化」，
 * 详见 [spreadsheetRow]。
 *
 * 之所以要支持 xlsx：**微信现在导出的就是 xlsx**，只认 CSV 等于这个功能用不了。
 */
object BillImporter {

  // 列名候选，按「更具体优先」排列：先匹配到的胜出
  private val TIME_CANDIDATES =
    listOf("交易时间", "交易创建时间", "付款时间", "记账时间", "交易日期", "日期", "时间")
  private val AMOUNT_CANDIDATES =
    listOf("金额(元)", "交易金额", "发生金额", "金额", "amount")
  private val DIRECTION_CANDIDATES = listOf("收/支", "收支类型", "收/支类型", "收支")
  private val COUNTERPARTY_CANDIDATES = listOf("交易对方", "商户名称", "对方", "商户")
  private val PRODUCT_CANDIDATES = listOf("商品名称", "商品说明", "商品", "摘要")
  private val STATUS_CANDIDATES = listOf("当前状态", "交易状态", "状态")
  private val ORDER_ID_CANDIDATES = listOf("交易单号", "交易号", "订单号", "商户单号")
  private val NOTE_CANDIDATES = listOf("备注", "说明")

  /** 这些状态不是真实发生的收支，跳过。刻意**不**包含「退款」：退款在账单里是独立的收入行。 */
  private val CLOSED_STATUS_KEYWORDS = listOf("交易关闭", "已关闭", "交易失败", "已撤销", "已取消")

  private val DATE_TIME_REGEX =
    Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T]?(\d{1,2}):(\d{2})(?::(\d{2}))?)?""")

  /**
   * 解析账单文件。
   *
   * 先看文件头：`PK` 开头的 ZIP 走 xlsx 读取（xlsx 本质是 ZIP），
   * 其余按文本处理（微信 CSV 是 UTF-8、支付宝是 GBK，见 [TextDecoding]）。
   * 按内容而不是按扩展名判断，是因为 SAF 给出的文件名未必可信。
   */
  fun parse(bytes: ByteArray, zone: ZoneId): ImportParseResult {
    if (XlsxReader.isZip(bytes)) {
      val rows =
        XlsxReader.read(bytes)
          ?: return ImportParseResult.Unrecognized(
            "这是一个压缩包，但里面读不出 Excel 表格。微信的账单邮件有时是加密的 zip，" +
              "请先解压出 .xlsx（或 .csv）文件再导入。"
          )
      return parseRows(rows, zone, spreadsheet = true)
    }
    return parseRows(CsvReader.read(TextDecoding.decode(bytes)), zone, spreadsheet = false)
  }

  /** 直接解析文本（便于测试与分析）。 */
  fun parseText(text: String, zone: ZoneId): ImportParseResult =
    parseRows(CsvReader.read(text), zone, spreadsheet = false)

  /**
   * @param spreadsheet 是否来自 xlsx。为 true 时会对时间列与金额列做单元格值归一化。
   */
  private fun parseRows(
    rows: List<List<String>>,
    zone: ZoneId,
    spreadsheet: Boolean,
  ): ImportParseResult {
    if (rows.isEmpty()) {
      return ImportParseResult.Unrecognized(
        if (spreadsheet) "表格里没有内容。" else "文件是空的，或不是 CSV 格式。"
      )
    }

    val headerIndex =
      findHeaderRow(rows)
        ?: return ImportParseResult.Unrecognized(
          "没找到账单表头。请确认导出的是微信或支付宝的账单文件（支持 CSV 与 Excel 的 .xlsx）。"
        )

    val headers = rows[headerIndex].map(::normalizeHeader)
    val format = detectFormat(headers)
    val columns = mapColumns(headers)

    if (columns.time == null) {
      return ImportParseResult.Unrecognized("表头里没有时间列，无法导入。")
    }
    if (columns.amount == null) {
      return ImportParseResult.Unrecognized("表头里没有金额列，无法导入。")
    }

    val entries = mutableListOf<ImportedEntry>()
    var skipped = 0
    rows.drop(headerIndex + 1).forEach { raw ->
      val row = if (spreadsheet) spreadsheetRow(raw, columns) else raw
      val entry = parseRow(row, columns, format, zone)
      if (entry == null) skipped++ else entries.add(entry)
    }

    if (entries.isEmpty()) {
      return ImportParseResult.Unrecognized("表头识别成功，但没有解析出任何有效账目（共 $skipped 行被跳过）。")
    }
    return ImportParseResult.Ok(format = format, entries = entries, skippedRows = skipped)
  }

  // ---------- 表格单元格归一化 ----------

  /**
   * 把 xlsx 的单元格值整理成与 CSV 等价的文本。
   *
   * 只动**时间列**与**金额列**，因为只有这两列在两种来源下形态不同：
   *
   *  - **时间**：xlsx 里若是真日期单元格，读出来是 Excel 序列号（`45352.520833`），
   *    正则匹配不到，[parseTime] 会返回 null，整行被当成无效行丢掉。
   *  - **金额**：数值单元格写的是 `23` 或 `23.000000000000004` 这类浮点尾巴，
   *    直接交给 [parseAmountMinor] 会因为「超过 2 位小数」被拒，整行丢失。
   *
   * 其余列（收/支、交易对方、交易单号……）两种来源都只是文本，不需要处理。
   */
  private fun spreadsheetRow(row: List<String>, columns: Columns): List<String> {
    val timeIndex = columns.time
    val amountIndex = columns.amount
    if (timeIndex == null && amountIndex == null) return row

    val out = row.toMutableList()

    timeIndex?.let { index ->
      if (index < out.size) {
        val raw = out[index].trim()
        // 已经是可读日期就原样保留，避免把「2026-10-10 12:30:00」当数字再算一遍
        if (raw.isNotEmpty() && !DATE_TIME_REGEX.containsMatchIn(raw)) {
          excelSerialToText(raw)?.let { out[index] = it }
        }
      }
    }

    amountIndex?.let { index ->
      if (index < out.size) {
        val decimal = out[index].trim().toBigDecimalOrNull()
        if (decimal != null) {
          out[index] = decimal.setScale(2, RoundingMode.HALF_UP).toPlainString()
        }
      }
    }

    return out
  }

  /** Excel 1900 日期系统的起点。序列号 1 = 1900-01-01。 */
  private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

  private val SERIAL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  /**
   * 序列号的合理值域。
   *
   * 下界取 20000（约 1954 年）：个人账单不可能早于这个年份，而这条的主要作用是
   * **挡住那些「碰巧落在时间列里的普通数字」**——比如某份文件把序号或金额写进了
   * 时间列，`32.5` 之类的值如果按序列号算会得到一个看似合理的 1900 年日期，
   * 那种错误数据比直接丢弃难发现得多。
   *
   * 上界取 80000（约 2119 年），挡住明显异常的数值。
   */
  private const val MIN_SERIAL = 20_000.0
  private const val MAX_SERIAL = 80_000.0

  /**
   * Excel 日期序列号 → `yyyy-MM-dd HH:mm:ss`。
   *
   * 序列号的整数部分是天数、小数部分是当天的时刻（0.5 = 12:00），所以拆开算：
   * 先取整天，再把小数部分换算成秒。**不要**整体乘 86400000 再取整——
   * 那样会因为浮点误差丢掉几秒。
   *
   * （Excel 有个著名的 1900 闰年 bug，序列 60 对应不存在的 1900-02-29；
   * 但账单日期都在 1900-03-01 之后，用 1899-12-30 作起点就是对的。）
   */
  internal fun excelSerialToText(raw: String): String? {
    val serial = raw.toDoubleOrNull() ?: return null
    if (serial < MIN_SERIAL || serial > MAX_SERIAL) return null

    val days = floor(serial).toLong()
    val secondsOfDay = ((serial - days) * 86_400.0).roundToLong()
    return try {
      EXCEL_EPOCH.plusDays(days).atStartOfDay().plusSeconds(secondsOfDay).format(SERIAL_FORMAT)
    } catch (e: DateTimeException) {
      null
    }
  }

  // ---------- 表头 ----------

  /**
   * 找出表头行。
   *
   * 判据是「至少有 2 个单元格，且其中一格像时间列」。
   *
   * 为什么不要求同时具备时间列与金额列：那样一来「有表头但缺金额列」会落进
   * 「没找到表头」分支，用户看到的提示是「请确认导出的是微信或支付宝账单」——
   * 对一个明显是账单的文件，这是误导。先定位表头、再逐列检查，才能给出精确原因。
   *
   * 「至少 2 个单元格」是为了排除账单前面的说明行：微信/支付宝的说明行里
   * 也会出现「起始时间」「终止时间」这类字样，但它们不含逗号，只有一格。
   *
   * 不过 xlsx 里的说明行**有可能占两格**（A 列写「起始时间」、B 列写值），
   * 这时上面的规则会误判。因此再加一层择优：**优先选同时含金额列的候选行**，
   * 一个都不满足时才退回第一个候选。这样既纠正了 xlsx 的误判，
   * 又保留了「缺金额列」时给出精确原因的能力。
   */
  private fun findHeaderRow(rows: List<List<String>>): Int? {
    val candidates =
      rows.indices.filter { index ->
        val row = rows[index]
        row.size >= 2 && row.any { cell -> TIME_CANDIDATES.any { normalizeHeader(cell).contains(it) } }
      }
    if (candidates.isEmpty()) return null

    return candidates.firstOrNull { index ->
      rows[index].any { cell -> AMOUNT_CANDIDATES.any { normalizeHeader(cell).contains(it) } }
    } ?: candidates.first()
  }

  /** 归一化：去掉空白、把全角括号换成半角、转小写。 */
  internal fun normalizeHeader(raw: String): String =
    raw.trim().replace(" ", "").replace("　", "").replace('（', '(').replace('）', ')').lowercase()

  private fun detectFormat(headers: List<String>): BillFormat {
    val joined = headers.joinToString("|")
    return when {
      joined.contains("当前状态") || joined.contains("商品说明") || joined.contains("交易单号") ->
        BillFormat.WECHAT
      joined.contains("交易号") || joined.contains("交易来源地") || joined.contains("资金状态") ->
        BillFormat.ALIPAY
      else -> BillFormat.GENERIC
    }
  }

  private class Columns(
    val time: Int?,
    val amount: Int?,
    val direction: Int?,
    val counterparty: Int?,
    val product: Int?,
    val status: Int?,
    val orderId: Int?,
    val note: Int?,
  )

  private fun mapColumns(headers: List<String>): Columns =
    Columns(
      // 外层遍历候选名、内层找列：保证「更具体的列名」优先命中
      time = findColumn(headers, TIME_CANDIDATES),
      amount = findColumn(headers, AMOUNT_CANDIDATES),
      direction = findColumn(headers, DIRECTION_CANDIDATES),
      counterparty = findColumn(headers, COUNTERPARTY_CANDIDATES),
      product = findColumn(headers, PRODUCT_CANDIDATES),
      status = findColumn(headers, STATUS_CANDIDATES),
      orderId = findColumn(headers, ORDER_ID_CANDIDATES),
      note = findColumn(headers, NOTE_CANDIDATES),
    )

  private fun findColumn(headers: List<String>, candidates: List<String>): Int? =
    candidates.firstNotNullOfOrNull { candidate ->
      headers.indexOfFirst { it.contains(candidate) }.takeIf { it >= 0 }
    }

  // ---------- 行 ----------

  private fun parseRow(
    row: List<String>,
    columns: Columns,
    format: BillFormat,
    zone: ZoneId,
  ): ImportedEntry? {
    fun cell(index: Int?): String? =
      index?.let { row.getOrNull(it) }?.let(::cleanText)

    val status = cell(columns.status)
    if (status != null && CLOSED_STATUS_KEYWORDS.any { status.contains(it) }) return null

    val occurredAt = cell(columns.time)?.let { parseTime(it, zone) } ?: return null
    val rawAmount = cell(columns.amount) ?: return null
    val signedMinor = parseAmountMinor(rawAmount) ?: return null

    val direction = cell(columns.direction)
    val type =
      when {
        direction == null ->
          // 没有「收/支」列时按符号判断：负数为支出（多数银行/通用账单的惯例）
          if (signedMinor < 0) TransactionType.EXPENSE else TransactionType.INCOME
        direction.contains("支出") -> TransactionType.EXPENSE
        direction.contains("收入") -> TransactionType.INCOME
        // 「不计收支」等：本身不是收支，跳过
        else -> return null
      }

    val amountMinor = abs(signedMinor)
    if (amountMinor <= 0L) return null

    val counterparty = cell(columns.counterparty)
    val product = cell(columns.product)
    val rawNote = cell(columns.note)

    val sourceId =
      cell(columns.orderId)?.let { "${format.name}:$it" }
        ?: "${format.name}:$occurredAt:$amountMinor:${type.name}:${counterparty.orEmpty()}"

    return ImportedEntry(
      sourceId = sourceId,
      occurredAt = occurredAt,
      type = type,
      amountMinor = amountMinor,
      counterparty = counterparty,
      product = product,
      note = product ?: rawNote,
      suggestedIconKey =
        CategoryGuesser.guess(type, listOfNotNull(product, counterparty, rawNote)),
    )
  }

  /** 账单里的占位符（`/`、`-`、`无`）等同于空，否则它们会被当成有效备注。 */
  internal fun cleanText(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed in PLACEHOLDERS) return null
    return trimmed
  }

  private val PLACEHOLDERS = setOf("/", "-", "—", "--", "无", "N/A", "null")

  /** 金额解析：去掉货币符号与千分位，返回**分**（可为负）。 */
  internal fun parseAmountMinor(raw: String): Long? {
    val cleaned =
      raw.replace("¥", "")
        .replace("￥", "")
        .replace(",", "")
        .replace(" ", "")
        .replace("　", "")
        .trim()
    if (cleaned.isEmpty()) return null

    val negative = cleaned.startsWith("-")
    val body = cleaned.removePrefix("-").removePrefix("+")
    val minor = Money.parse(body) ?: return null
    return if (negative) -minor else minor
  }

  /** 时间解析：容忍「日期与时间之间没有空格」以及缺失的秒。 */
  internal fun parseTime(raw: String, zone: ZoneId): Long? {
    val match = DATE_TIME_REGEX.find(raw) ?: return null
    return try {
      val date =
        LocalDate.of(
          match.groupValues[1].toInt(),
          match.groupValues[2].toInt(),
          match.groupValues[3].toInt(),
        )
      val hour = match.groupValues[4].toIntOrNull() ?: 0
      val minute = match.groupValues[5].toIntOrNull() ?: 0
      val second = match.groupValues[6].toIntOrNull() ?: 0
      date.atTime(hour, minute, second).atZone(zone).toInstant().toEpochMilli()
    } catch (e: DateTimeException) {
      null
    }
  }
}
