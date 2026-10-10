package com.moneytracker.app.data.importer

import com.moneytracker.app.data.importer.XlsxFixture.Cell
import com.moneytracker.app.data.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账单导入的 xlsx 分支测试。
 *
 * 微信现在导出的就是 xlsx，所以这条路径不是「顺便支持」，而是主路径之一。
 * 样本按 xlsx 的真实特征构造：**说明行只占 A 列**、时间列是**日期序列号**、
 * 金额列是**数值单元格**、以及中间列可能整列缺失。
 */
class BillImporterXlsxTest {

  private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

  /** Excel 日期序列号：1900 日期系统，1899-12-30 记为 0。 */
  private fun serial(date: LocalDate, hour: Int = 0, minute: Int = 0): String =
    (
      ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), date).toDouble() +
        (hour * 60 + minute) / 1440.0
    )
      .toString()

  private fun row(vararg cells: Cell): List<Cell> = cells.toList()

  private fun text(value: String): Cell = Cell.Text(value)

  private val header =
    listOf(
      "交易时间",
      "交易类型",
      "交易对方",
      "商品",
      "收/支",
      "金额(元)",
      "支付方式",
      "当前状态",
      "交易单号",
      "商户单号",
      "备注",
    ).map { Cell.Text(it) }

  /** 微信账单的 xlsx 形态：6 行说明 + 表头 + 数据。 */
  private fun wechatSheet(): ByteArray =
    XlsxFixture.xlsx(
      listOf(
        // 说明行只有 A 列有内容——这正是「至少 2 格」这条表头判据要排除的情况
        row(text("微信支付账单明细")),
        row(text("微信昵称：[测试用户]")),
        row(text("起始时间：[2026-01-01 00:00:00] 终止时间：[2026-01-31 23:59:59]")),
        row(text("导出类型：[全部]")),
        row(text("导出时间：[2026-02-01 10:00:00]")),
        row(text("共 4 笔记录")),
        header,
        row(
          Cell.Number(serial(LocalDate.of(2026, 1, 5), 12, 30)),
          text("商户消费"),
          text("某某餐厅"),
          text("午餐"),
          text("支出"),
          Cell.Number("32.5"), // 数值金额，且只有一位小数
          text("零钱"),
          text("支付成功"),
          text("4200001111111111"),
          text("merchant1"),
          Cell.Blank, // 备注为空
        ),
        row(
          Cell.Number(serial(LocalDate.of(2026, 1, 6), 8, 15)),
          text("商户消费"),
          text("地铁公司"),
          text("地铁票"),
          text("支出"),
          text("¥4.00"), // 文本金额，带货币符号
          text("零钱通"),
          text("支付成功"),
          text("4200002222222222"),
          text("merchant2"),
          text("/"), // 占位符，应等同于空
        ),
        row(
          Cell.Number(serial(LocalDate.of(2026, 1, 10), 20, 0)),
          text("转账"),
          text("张三"),
          Cell.Blank, // 商品为空
          text("收入"),
          Cell.Number("100"),
          text("零钱"),
          text("已存入零钱"),
          text("4200003333333333"),
          text("merchant3"),
          text("还钱"),
        ),
        row(
          Cell.Number(serial(LocalDate.of(2026, 1, 15), 9, 0)),
          text("零钱通转出"),
          text("零钱通"),
          Cell.Blank,
          text("不计收支"),
          Cell.Number("500"),
          text("零钱通"),
          text("已转出"),
          text("4200004444444444"),
          text("merchant4"),
          Cell.Blank,
        ),
      )
    )

  // ---------- 端到端 ----------

  @Test
  fun wechat_xlsx_is_recognized_end_to_end() {
    val result = BillImporter.parse(wechatSheet(), zone)

    assertTrue("应识别为账单，实际是 $result", result is ImportParseResult.Ok)
    val ok = result as ImportParseResult.Ok
    assertEquals(BillFormat.WECHAT, ok.format)
    assertEquals("「不计收支」那一行应被跳过", 1, ok.skippedRows)
    assertEquals(3, ok.entries.size)
  }

  @Test
  fun numeric_date_serial_becomes_a_real_timestamp() {
    val ok = BillImporter.parse(wechatSheet(), zone) as ImportParseResult.Ok
    val lunch = ok.entries.first { it.counterparty == "某某餐厅" }

    assertEquals(at(2026, 1, 5, 12, 30), lunch.occurredAt)
  }

  @Test
  fun numeric_amount_cell_is_parsed_as_money() {
    val ok = BillImporter.parse(wechatSheet(), zone) as ImportParseResult.Ok
    val lunch = ok.entries.first { it.counterparty == "某某餐厅" }

    assertEquals(TransactionType.EXPENSE, lunch.type)
    assertEquals(3250L, lunch.amountMinor)
    assertEquals("午餐", lunch.product)
  }

  @Test
  fun text_amount_with_currency_symbol_still_works_in_xlsx() {
    val ok = BillImporter.parse(wechatSheet(), zone) as ImportParseResult.Ok
    val subway = ok.entries.first { it.counterparty == "地铁公司" }

    assertEquals(400L, subway.amountMinor)
    assertEquals(at(2026, 1, 6, 8, 15), subway.occurredAt)
  }

  @Test
  fun placeholder_note_becomes_empty_and_falls_back_to_the_product() {
    val ok = BillImporter.parse(wechatSheet(), zone) as ImportParseResult.Ok
    val subway = ok.entries.first { it.counterparty == "地铁公司" }

    assertEquals("备注「/」应视为空，退回商品名", "地铁票", subway.note)
  }

  @Test
  fun missing_middle_column_does_not_break_the_income_row() {
    // 该行「商品」列是空的；若解析时列错位，这里会变成商品有值、收/支为空
    val ok = BillImporter.parse(wechatSheet(), zone) as ImportParseResult.Ok
    val transferred = ok.entries.first { it.counterparty == "张三" }

    assertEquals(TransactionType.INCOME, transferred.type)
    assertEquals(10000L, transferred.amountMinor)
    assertNull(transferred.product)
    assertEquals("还钱", transferred.note)
    assertEquals(at(2026, 1, 10, 20, 0), transferred.occurredAt)
  }

  @Test
  fun trade_number_is_used_as_the_dedup_key() {
    val ok = BillImporter.parse(wechatSheet(), zone) as ImportParseResult.Ok
    val lunch = ok.entries.first { it.counterparty == "某某餐厅" }

    // 去重键要能跨「CSV 导入」与「xlsx 导入」对齐，因此只由格式名与单号组成
    assertEquals("WECHAT:4200001111111111", lunch.sourceId)
  }

  // ---------- 支付宝的 xlsx ----------

  @Test
  fun alipay_xlsx_is_detected_by_its_headers() {
    val sheet =
      XlsxFixture.xlsx(
        listOf(
          row(text("支付宝交易记录明细查询")),
          listOf(
              "交易号",
              "交易创建时间",
              "交易对方",
              "商品名称",
              "金额（元）",
              "收/支",
              "交易状态",
            )
            .map { Cell.Text(it) },
          row(
            text("2026010522001111111"),
            Cell.Number(serial(LocalDate.of(2026, 1, 5), 13, 0)),
            text("某某超市"),
            text("日用品"),
            Cell.Number("50"),
            text("支出"),
            text("交易成功"),
          ),
        )
      )

    val ok = BillImporter.parse(sheet, zone) as ImportParseResult.Ok

    assertEquals(BillFormat.ALIPAY, ok.format)
    assertEquals(1, ok.entries.size)
    assertEquals(5000L, ok.entries.first().amountMinor)
    assertEquals(at(2026, 1, 5, 13, 0), ok.entries.first().occurredAt)
  }

  // ---------- 表头定位 ----------

  @Test
  fun two_column_preamble_row_containing_a_time_word_is_not_mistaken_for_the_header() {
    // xlsx 里说明行可能是 A 列标题 + B 列值，于是「至少有 2 格且含时间字样」成立。
    // 只有「优先选同时含金额列的候选行」才能跳过它。
    val sheet =
      XlsxFixture.xlsx(
        listOf(
          row(text("起始时间"), text("[2026-01-01 00:00:00]")),
          row(text("终止时间"), text("[2026-01-31 23:59:59]")),
          header,
          row(
            Cell.Number(serial(LocalDate.of(2026, 1, 5), 12, 30)),
            text("商户消费"),
            text("某某餐厅"),
            text("午餐"),
            text("支出"),
            Cell.Number("32.5"),
            text("零钱"),
            text("支付成功"),
            text("4200001111111111"),
            text("merchant1"),
            Cell.Blank,
          ),
        )
      )

    val result = BillImporter.parse(sheet, zone)

    assertTrue("应认到真正的表头，实际是 $result", result is ImportParseResult.Ok)
    assertEquals(1, (result as ImportParseResult.Ok).entries.size)
  }

  @Test
  fun a_header_without_an_amount_column_still_reports_the_precise_reason() {
    // 这条守住 findHeaderRow 的原始设计意图：不能因为「缺金额列」就退化成
    // 「没找到账单表头」，那对用户是误导。
    val sheet =
      XlsxFixture.xlsx(
        listOf(
          row(text("交易时间"), text("交易对方"), text("收/支")),
          row(text("2026-01-05 12:30:00"), text("某某餐厅"), text("支出")),
        )
      )

    val result = BillImporter.parse(sheet, zone)

    assertTrue(result is ImportParseResult.Unrecognized)
    assertEquals("表头里没有金额列，无法导入。", (result as ImportParseResult.Unrecognized).reason)
  }

  // ---------- 失败路径与回归 ----------

  @Test
  fun encrypted_zip_without_a_worksheet_gives_an_actionable_message() {
    val result = BillImporter.parse(XlsxFixture.zipWithoutWorksheet(), zone)

    assertTrue(result is ImportParseResult.Unrecognized)
    val reason = (result as ImportParseResult.Unrecognized).reason
    assertTrue("应提示先解压，实际是：$reason", reason.contains("压缩包"))
    assertTrue("应提示先解压，实际是：$reason", reason.contains("解压"))
  }

  @Test
  fun plain_csv_bytes_still_take_the_text_path() {
    // 回归：加了 ZIP 判定之后，CSV 不能被误判
    val csv = "交易时间,交易对方,收/支,金额(元)\n2026-01-05 12:30:00,某某餐厅,支出,¥32.50\n"

    val ok = BillImporter.parse(csv.toByteArray(Charsets.UTF_8), zone) as ImportParseResult.Ok

    assertEquals(1, ok.entries.size)
    assertEquals(3250L, ok.entries.first().amountMinor)
  }

  // ---------- 序列号换算 ----------

  @Test
  fun serial_conversion_matches_known_excel_reference_values() {
    // 这两个值是 Excel 里的公开基准：2000-01-01 = 36526、2024-03-01 = 45352
    assertEquals("2000-01-01 00:00:00", BillImporter.excelSerialToText("36526"))
    assertEquals("2024-03-01 12:30:00", BillImporter.excelSerialToText("45352.520833"))
    assertEquals("2026-01-05 12:30:00", BillImporter.excelSerialToText("46027.520833333336"))
  }

  @Test
  fun fractional_serial_keeps_the_time_of_day() {
    // 0.25 天 = 06:00，0.5 = 12:00，0.75 = 18:00
    assertEquals("2026-01-05 06:00:00", BillImporter.excelSerialToText("46027.25"))
    assertEquals("2026-01-05 12:00:00", BillImporter.excelSerialToText("46027.5"))
    assertEquals("2026-01-05 18:00:00", BillImporter.excelSerialToText("46027.75"))
  }

  @Test
  fun out_of_range_values_are_not_treated_as_dates() {
    assertNull(BillImporter.excelSerialToText("0.5"))
    assertNull(BillImporter.excelSerialToText("999999"))
    assertNull(BillImporter.excelSerialToText("32.50"))
    assertNull(BillImporter.excelSerialToText("不是数字"))
  }

  @Test
  fun a_readable_date_string_is_left_alone_by_serial_conversion() {
    // xlsx 里时间列也可能是普通文本，这种情况不能被当成序列号
    val sheet =
      XlsxFixture.xlsx(
        listOf(
          listOf("交易时间", "交易对方", "收/支", "金额(元)").map { Cell.Text(it) },
          row(text("2026-01-05 12:30:00"), text("某某餐厅"), text("支出"), Cell.Number("32.5")),
        )
      )

    val ok = BillImporter.parse(sheet, zone) as ImportParseResult.Ok

    assertEquals(at(2026, 1, 5, 12, 30), ok.entries.first().occurredAt)
  }

  @Test
  fun floating_point_noise_in_the_amount_is_rounded() {
    // Excel 的数值单元格可能出现 32.499999999999996 这类浮点尾巴
    val sheet =
      XlsxFixture.xlsx(
        listOf(
          listOf("交易时间", "交易对方", "收/支", "金额(元)").map { Cell.Text(it) },
          row(
            text("2026-01-05 12:30:00"),
            text("某某餐厅"),
            text("支出"),
            Cell.Number("32.499999999999996"),
          ),
        )
      )

    val ok = BillImporter.parse(sheet, zone) as ImportParseResult.Ok

    assertEquals(3250L, ok.entries.first().amountMinor)
  }
}
