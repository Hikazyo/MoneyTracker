package com.moneytracker.app.data.ocr

import com.moneytracker.app.data.model.TransactionType
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 解析层测试。
 *
 * fixture 不是手写的理想数据，而是 spike 时从 ML Kit 真实输出里抓下来的
 * （`app/src/test/resources/ocr/sample*.txt`，格式 `left,top,right,bottom<TAB>text`）。
 * 因此这些测试同时也在守护「识别错字不要紧，解析要兜得住」这件事——
 * 比如 `一¥14.98`（负号被识别成汉字）必须仍判为支出。
 */
class ScreenshotParserTest {

  private val zone = ZoneId.of("Asia/Shanghai")
  private val parser = ScreenshotParser(zone = zone)
  private val fallback = LocalDate.of(2026, 10, 28)

  // ---------- 微信（浅色） ----------

  @Test
  fun wechat_light_parses_all_nine_rows() {
    val result = parse("sample1.txt", "Screenshot_20261028_153409.jpg")

    assertEquals(ScreenshotSource.WECHAT, result.source)
    assertEquals(9, result.entries.size)
    assertEquals(
      listOf(300L, 2190L, 1759L, 150L, 1510L, 180L, 1701L, 550L, 300L),
      result.entries.map { it.amountMinor },
    )
    assertEquals(TransactionType.INCOME, result.entries[0].type)
    assertTrue(result.entries.drop(1).all { it.type == TransactionType.EXPENSE })
  }

  @Test
  fun wechat_light_keeps_each_row_own_date() {
    val result = parse("sample1.txt", "Screenshot_20261028_153409.jpg")

    assertEquals(
      listOf(
        "2026-10-24 19:32",
        "2026-10-24 18:48",
        "2026-10-23 18:54",
        "2026-10-22 22:17",
        "2026-10-22 18:09",
        "2026-10-22 15:31",
        "2026-10-22 11:52",
        "2026-10-22 09:42",
        "2026-10-22 07:24",
      ),
      result.entries.map { stamp(it) },
    )
  }

  @Test
  fun wechat_light_strips_icon_noise_from_merchants() {
    val result = parse("sample1.txt", "Screenshot_20261028_153409.jpg")

    assertEquals("转账-来自666", result.entries[0].merchant)
    assertEquals("营养粥", result.entries[8].merchant)
    // 已知的识别错字原样保留：这不是解析层的锅，解析层不该「猜」正确商户名
    assertEquals("姜团", result.entries[5].merchant)
  }

  // ---------- 微信（深色） ----------

  @Test
  fun wechat_dark_parses_the_same_number_of_rows() {
    val result = parse("sample2.txt", "Screenshot_20261028_153431.jpg")

    assertEquals(ScreenshotSource.WECHAT, result.source)
    assertEquals(9, result.entries.size)
    assertEquals(
      listOf(1000L, 1000L, 5000L, 1380L, 500L, 1380L, 500L, 1970L, 650L),
      result.entries.map { it.amountMinor },
    )
    assertEquals(TransactionType.INCOME, result.entries[0].type)
    assertEquals(TransactionType.INCOME, result.entries[1].type)
  }

  // ---------- 支付宝（繁体） ----------

  @Test
  fun alipay_traditional_parses_seven_rows() {
    val result = parse("sample3.txt", "Screenshot_20261028_153457.jpg")

    assertEquals(ScreenshotSource.ALIPAY, result.source)
    assertEquals(7, result.entries.size)
    assertEquals(
      listOf(100L, 10L, 30000L, 100L, 100L, 2240L, 10L),
      result.entries.map { it.amountMinor },
    )
  }

  @Test
  fun alipay_unsigned_amount_is_income_but_flagged() {
    val result = parse("sample3.txt", "Screenshot_20261028_153457.jpg")

    // `0.10` 没有符号：按收入处理，但必须标记「方向待确认」，由用户在预览里决定
    val unsigned = result.entries[1]
    assertEquals(TransactionType.INCOME, unsigned.type)
    assertTrue(unsigned.directionUncertain)
    // 有显式负号的行不应被标记
    assertFalse(result.entries[0].directionUncertain)
  }

  @Test
  fun alipay_uses_the_label_on_the_row_as_category() {
    val result = parse("sample3.txt", "Screenshot_20261028_153457.jpg")

    assertEquals("餐飲美食", result.entries[5].categoryLabel)
    assertEquals("food", result.entries[5].suggestedIconKey)
    assertEquals("教育培訓", result.entries[2].categoryLabel)
    assertEquals("education", result.entries[2].suggestedIconKey)
  }

  @Test
  fun alipay_transfer_label_marks_row_as_suspicious() {
    val result = parse("sample3.txt", "Screenshot_20261028_153457.jpg")

    // 支付宝小荷包-自动攒 / 賬戶存取：资金划转，不是消费
    // 注意标签本身被识别成了「脹戶存取」——繁体加 OCR 错字，规则必须认这个变体
    assertTrue(result.entries[0].suspicious)
    assertEquals("脹戶存取", result.entries[0].categoryLabel)
  }

  // ---------- 银行流水 ----------

  @Test
  fun bank_parses_nine_rows_and_reports_the_clipped_one() {
    val result = parse("sample4.txt", "Screenshot_20261028_153548.jpg")

    assertEquals(ScreenshotSource.BANK, result.source)
    assertEquals(9, result.entries.size)
    // 最后一行（瑞幸咖啡 07:30）的金额被屏幕底部裁掉了，必须丢弃并计数，不能猜
    assertEquals(1, result.skippedRows)
  }

  @Test
  fun bank_minus_sign_read_as_chinese_one_still_counts_as_expense() {
    val result = parse("sample4.txt", "Screenshot_20261028_153548.jpg")

    // 原图是 `-¥14.98`，OCR 给了 `一¥14.98`
    val row = result.entries[4]
    assertEquals(14_98L, row.amountMinor)
    assertEquals(TransactionType.EXPENSE, row.type)
  }

  @Test
  fun bank_balance_column_never_becomes_an_entry() {
    val result = parse("sample4.txt", "Screenshot_20261028_153548.jpg")

    val balanceAmounts = setOf(226_137L, 227_517L, 228_807L, 228_907L, 229_187L, 230_685L)
    assertTrue(result.entries.none { it.amountMinor in balanceAmounts })
    assertTrue(result.entries.none { it.merchant?.contains("余额") == true })
  }

  @Test
  fun bank_group_headers_supply_dates_for_relative_rows() {
    val result = parse("sample4.txt", "Screenshot_20261028_153548.jpg")

    // 今天 / 昨天 以文件名为基准（2026-10-28）；10.26 是绝对日期
    assertEquals("2026-10-28 11:47", stamp(result.entries[0]))
    assertEquals("2026-10-27 11:42", stamp(result.entries[4]))
    assertEquals("2026-10-26 22:06", stamp(result.entries[6]))
  }

  // ---------- 单条规则 ----------

  @Test
  fun amount_rules_tolerate_the_known_ocr_variants() {
    assertEquals(1498L, AmountRules.parse("一¥14.98")?.minor)
    assertTrue(AmountRules.parse("一¥14.98")!!.negative)
    assertEquals(2190L, AmountRules.parse("-21.90")?.minor)
    assertEquals(300L, AmountRules.parse("+3.00")?.minor)
    assertFalse(AmountRules.parse("+3.00")!!.negative)
    assertNull(AmountRules.parse("支出¥1466.71 收入¥615.90"))
    assertTrue(AmountRules.isBalanceLine("余额:¥2,261.37"))
    assertTrue(AmountRules.isBalanceLine("|余颤¥2,424.74"))
  }

  @Test
  fun date_rules_handle_both_date_styles() {
    assertEquals(10 to 24, DateRules.findMonthDay("10月24日 19:32"))
    assertEquals(10 to 26, DateRules.findMonthDay("10-26 22:16"))
    assertEquals(19 to 32, DateRules.findTime("10月24日 19:32"))
    assertNull(DateRules.findMonthDay("2026.10"))
    assertEquals(LocalDate.of(2026, 10, 28), DateRules.parseFileDate("Screenshot_20261028_153409.jpg"))
    assertEquals(DateRules.YearMonth(2026, 10), DateRules.findYearMonth("2026年10月ッ"))
    assertEquals(DateRules.YearMonth(2026, 10), DateRules.findYearMonth("2026.10▼银行卡▼"))
    assertNull(DateRules.findYearMonth("10月▼"))
  }

  @Test
  fun year_rolls_back_when_the_row_month_is_in_the_future() {
    val header = DateRules.YearMonth(2026, 1)
    // 1 月导入 12 月的截图，那笔消费属于去年
    assertEquals(2025, DateRules.resolveYear(null, 12, LocalDate.of(2026, 1, 5)))
    assertEquals(2026, DateRules.resolveYear(header, 12, LocalDate.of(2026, 1, 5)))
  }

  // ---------- 工具 ----------

  /**
   * 读取 fixture。
   *
   * `ocr` 目录下的 txt 是**真实账单**的识别输出，因此不随仓库发布（见 `.git/info/exclude`）。
   * 在没有这些文件的环境里（别人 clone、CI），相关用例自动跳过而不是报错——
   * 跳过意味着「本机没有样本」，不是「解析坏了」，两者不该混为一谈。
   */
  private fun parse(fixture: String, fileName: String): ScreenshotParser.Result {
    val lines = loadFixture(fixture)
    Assume.assumeTrue("缺少本机 fixture ocr/$fixture（含真实账单，不入库），跳过", lines != null)
    return parser.parse(
      lines = lines!!,
      imageWidth = 1080,
      imageHeight = 2400,
      imageKey = "test1234",
      fileName = fileName,
      fallbackDate = fallback,
    )
  }

  private fun stamp(entry: ScreenshotParser.Entry): String =
    Instant.ofEpochMilli(entry.occurredAtMillis)
      .atZone(zone)
      .toLocalDateTime()
      .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

  private fun loadFixture(name: String): List<OcrLine>? {
    val stream = javaClass.classLoader?.getResourceAsStream("ocr/$name") ?: return null
    return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
      lines.filter { it.isNotBlank() }.map { line ->
        val tab = line.indexOf('\t')
        require(tab > 0) { "bad fixture line: $line" }
        val box = line.substring(0, tab).split(',')
        OcrLine(
          text = line.substring(tab + 1),
          left = box[0].toInt(),
          top = box[1].toInt(),
          right = box[2].toInt(),
          bottom = box[3].toInt(),
        )
      }.toList()
    }
  }
}
