package com.moneytracker.app.data.ocr

import com.moneytracker.app.data.importer.CategoryGuesser
import com.moneytracker.app.data.model.TransactionType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * 把一屏账单截图的 OCR 结果解析成账目。
 *
 * ## 为什么以「金额」为锚点，而不是先切行
 *
 * 一屏 8~10 笔，每行的构成是「图标 + 商户 + 时间 + 金额」，但三家的排版并不一样：
 * 微信是两行（商户 / 时间），支付宝是三行（商户 / 分类 / 时间），银行把时间跟卡号写在
 * 同一行、金额与商户同一行、还多一列余额。
 *
 * 唯一的共同点是：**金额永远右对齐、且格式最规整**。所以这里先认出所有金额行当作锚点，
 * 再围绕每个锚点在垂直距离内找它自己的商户、日期、分类。这样换排版时规则不用重写，
 * 而且金额列的错位不会被误当成新的一行。
 *
 * ## 解析层不依赖 ML Kit
 *
 * 入参只有 [OcrLine]，因此全部规则都能在 JVM 单测里跑（fixture 就是 spike 抓到的真实输出）。
 */
class ScreenshotParser(private val zone: ZoneId = ZoneId.systemDefault()) {

  /**
   * 一条解析出的账目。
   *
   * 刻意不复用 `ImportedEntry`：截图识别多出三个「需要用户确认」的标记，
   * 而 CSV 导入没有这些概念。落库前再由界面层转成 `ImportedEntry`。
   */
  data class Entry(
    /** 去重键：`SHOT:<图片哈希前16位>:<行序号>`。同一张图导两次会被唯一索引拦住。 */
    val sourceId: String,
    val occurredAtMillis: Long,
    val type: TransactionType,
    val amountMinor: Long,
    val merchant: String?,
    /** 支付宝行内自带的分类标签，如 `餐飲美食`。没有则为 null。 */
    val categoryLabel: String?,
    val suggestedIconKey: String?,
    /** 疑似资金划转（自动攒、转账、退款…），预览里默认不勾选。 */
    val suspicious: Boolean,
    /** 日期或时间不是从这一行读出来的（靠页头/文件名/当天兜底），预览里需要高亮。 */
    val timeUncertain: Boolean,
    /** 金额行没有符号，方向是按平台惯例推的。 */
    val directionUncertain: Boolean,
  )

  data class Result(
    val source: ScreenshotSource,
    val entries: List<Entry>,
    /** 看起来是交易行、但金额缺失（典型情况：最后一行被屏幕底部裁掉）而跳过的行数。 */
    val skippedRows: Int,
  )

  fun parse(
    lines: List<OcrLine>,
    imageWidth: Int,
    imageHeight: Int,
    imageKey: String,
    fileName: String? = null,
    fallbackDate: LocalDate = LocalDate.now(zone),
  ): Result {
    if (lines.isEmpty() || imageWidth <= 0 || imageHeight <= 0) {
      return Result(ScreenshotSource.UNKNOWN, emptyList(), 0)
    }

    val source = ScreenshotSource.detect(lines)
    // 「今天/昨天」和缺年份时都以文件名为准，其次才是导入当天：
    // 用户常常隔几天才导入，用导入当天会把上个月的消费记到这周。
    val reference = DateRules.parseFileDate(fileName) ?: fallbackDate
    val headerYearMonth = lines.firstNotNullOfOrNull { DateRules.findYearMonth(it.text) }

    val rows = lines.sortedBy { it.centerY }
    val anchorPositions = rows.indices.filter { isAnchor(rows[it], imageWidth, imageHeight) }
    if (anchorPositions.isEmpty()) {
      return Result(source, emptyList(), 0)
    }

    val consumed = HashSet<Int>()
    val entries = ArrayList<Entry>(anchorPositions.size)

    anchorPositions.forEachIndexed { order, p ->
      val anchor = rows[p]
      val amount = AmountRules.parse(anchor.text) ?: return@forEachIndexed
      consumed += p

      val neighbors = nearbyRows(rows, p, imageHeight)
      val merchantPos = neighbors
        .filter { isMerchantCandidate(rows[it], anchor, imageWidth, imageHeight) }
        .minByOrNull { abs(rows[it].centerY - anchor.centerY) }
      // 商户与金额在同一水平带，日期/时间在商户**下方**。这条约束很关键：
      // 少了它，屏幕底部被裁掉的那一行会「偷」上一行的日期，静默记成错的日期。
      val belowMerchant = merchantPos?.let { rows[it].centerY } ?: anchor.centerY
      val dated = neighbors.filter { rows[it].centerY > belowMerchant - 10 }
      val datePos = dated
        .filter { DateRules.findMonthDay(rows[it].text) != null }
        .minByOrNull { abs(rows[it].centerY - anchor.centerY) }
      val timePos = dated
        .filter { DateRules.findTime(rows[it].text) != null }
        .minByOrNull { abs(rows[it].centerY - anchor.centerY) }
      // 分类标签要取**离本行最近**的那一个：支付宝是「商户 / 分类 / 时间」三行一段，
      // 取「第一个出现的」会把上一行或下一行的标签安到这一笔上（实测确实发生过）。
      val labelPos = dated
        .filter { AlipayCategoryMap.isLabel(rows[it].text) }
        .minByOrNull { abs(rows[it].centerY - anchor.centerY) }

      listOfNotNull(datePos, timePos, merchantPos, labelPos).forEach { consumed += it }

      val groupHeaderPos = nearestGroupHeader(rows, p, imageHeight)
      if (groupHeaderPos != null) consumed += groupHeaderPos

      val resolved = resolveDate(
        rowMonthDay = datePos?.let { DateRules.findMonthDay(rows[it].text) },
        headerYearMonth = headerYearMonth,
        groupHeader = groupHeaderPos?.let { DateRules.parseGroupHeader(rows[it].text) },
        reference = reference,
      )
      val clock = datePos?.let { DateRules.findTime(rows[it].text) }
        ?: timePos?.let { DateRules.findTime(rows[it].text) }

      val merchant = merchantPos
        ?.let { MerchantRules.clean(rows[it].text) }
        ?.takeIf { it.isNotBlank() }
      val label = labelPos?.let { rows[it].text.trim() }

      val type = if (amount.negative) TransactionType.EXPENSE else TransactionType.INCOME
      val suspicious =
        SuspiciousRules.isSuspicious(merchant, label) ||
          (label != null && AlipayCategoryMap.isTransferLabel(label))
      val iconKey =
        AlipayCategoryMap.iconKeyFor(label ?: "")
          ?: CategoryGuesser.guess(type, listOfNotNull(merchant, label))

      entries +=
        Entry(
          sourceId = "SHOT:$imageKey:${order + 1}",
          occurredAtMillis = toMillis(resolved, clock),
          type = type,
          amountMinor = amount.minor,
          merchant = merchant,
          categoryLabel = label,
          suggestedIconKey = iconKey,
          suspicious = suspicious,
          timeUncertain = resolved.uncertain || clock == null,
          directionUncertain = !amount.explicitSign,
        )
    }

    return Result(source, entries, countOrphanTimeRows(rows, consumed, imageHeight))
  }

  // ---------- 锚点 ----------

  private fun isAnchor(line: OcrLine, imageWidth: Int, imageHeight: Int): Boolean {
    // 状态栏里有「15:34」这类数字，但它不在内容区
    if (line.centerY < imageHeight * 0.08) return false
    // 金额列右对齐；余额列比金额列更靠左，靠这条先排除掉一部分
    if (line.left < imageWidth * 0.55) return false
    if (!AmountRules.isUsableAmountLine(line.text)) return false
    return AmountRules.parse(line.text) != null
  }

  private fun nearbyRows(rows: List<OcrLine>, anchorPos: Int, imageHeight: Int): List<Int> {
    val center = rows[anchorPos].centerY
    val radius = imageHeight * 0.15
    return rows.indices.filter { q ->
      if (q == anchorPos) return@filter false
      if (abs(rows[q].centerY - center) > radius) return@filter false
      val text = rows[q].text
      !AmountRules.isBalanceLine(text) && !AmountRules.isSummaryLine(text)
    }
  }

  private fun isMerchantCandidate(
    line: OcrLine,
    anchor: OcrLine,
    imageWidth: Int,
    imageHeight: Int,
  ): Boolean {
    val text = line.text
    if (text.isBlank()) return false
    if (line.left >= imageWidth * 0.70) return false
    if (AmountRules.parse(text) != null) return false
    if (DateRules.parseGroupHeader(text) != null) return false
    if (DateRules.findMonthDay(text) != null) return false
    if (AlipayCategoryMap.isLabel(text)) return false
    // 商户名与金额在同一水平带上：上方略高、下方略低都出现过
    val up = (imageHeight * 0.03).toInt()
    val down = (imageHeight * 0.02).toInt()
    return line.centerY in (anchor.centerY - up)..(anchor.centerY + down)
  }

  private fun nearestGroupHeader(rows: List<OcrLine>, anchorPos: Int, imageHeight: Int): Int? {
    val center = rows[anchorPos].centerY
    val maxDistance = imageHeight * 0.35
    return (anchorPos - 1 downTo 0)
      .filter { rows[it].centerY < center && center - rows[it].centerY <= maxDistance }
      .firstOrNull { DateRules.parseGroupHeader(rows[it].text) != null }
  }

  // ---------- 日期 ----------

  private data class ResolvedDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val uncertain: Boolean,
  )

  private fun resolveDate(
    rowMonthDay: Pair<Int, Int>?,
    headerYearMonth: DateRules.YearMonth?,
    groupHeader: DateRules.GroupHeader?,
    reference: LocalDate,
  ): ResolvedDate {
    if (rowMonthDay != null) {
      val (month, day) = rowMonthDay
      val year = DateRules.resolveYear(headerYearMonth, month, reference)
      return ResolvedDate(year, month, day, uncertain = false)
    }
    return when (groupHeader) {
      is DateRules.GroupHeader.Absolute -> {
        val year = DateRules.resolveYear(headerYearMonth, groupHeader.month, reference)
        ResolvedDate(year, groupHeader.month, groupHeader.day, uncertain = false)
      }
      is DateRules.GroupHeader.Relative -> {
        val date =
          when (groupHeader.relative) {
            DateRules.RelativeDay.TODAY -> reference
            DateRules.RelativeDay.YESTERDAY -> reference.minusDays(1)
          }
        ResolvedDate(date.year, date.monthValue, date.dayOfMonth, uncertain = false)
      }
      null -> ResolvedDate(reference.year, reference.monthValue, reference.dayOfMonth, true)
    }
  }

  private fun toMillis(date: ResolvedDate, clock: Pair<Int, Int>?): Long {
    val hour = clock?.first ?: 0
    val minute = clock?.second ?: 0
    return LocalDateTime.of(date.year, date.month, date.day, hour, minute)
      .atZone(zone)
      .toInstant()
      .toEpochMilli()
  }

  // ---------- 跳过行 ----------

  /**
   * 统计「有时间、但没配上金额」的行。
   *
   * 典型来源是屏幕底部被裁掉一半的最后一行——它的金额不在图里。
   * 这种行必须丢弃而不是猜，但要让用户知道「少了一笔」，所以计数回传。
   */
  private fun countOrphanTimeRows(
    rows: List<OcrLine>,
    consumed: Set<Int>,
    imageHeight: Int,
  ): Int =
    rows.indices.count { i ->
      i !in consumed &&
        rows[i].centerY > imageHeight * 0.1 &&
        DateRules.findTime(rows[i].text) != null
    }
}
