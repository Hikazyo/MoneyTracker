package com.moneytracker.app.data.importer

import com.moneytracker.app.data.model.TransactionType
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 账单导入解析测试。
 *
 * 样本刻意贴近真实文件：带十几行说明文字、全角括号、无空格分隔的日期、
 * 占位符「/」、以及只在支付宝出现的 GBK 编码。
 */
class BillImporterTest {

  private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

  /** 微信支付账单：UTF-8、半角括号、日期带空格。 */
  private val wechatBill =
    """
    微信支付账单明细
    微信昵称：[测试用户]
    起始时间：[2026-01-01 00:00:00] 终止时间：[2026-01-31 23:59:59]
    导出类型：[全部]
    导出时间：[2026-02-01 10:00:00]

    共 4 笔记录

    ----------------------微信支付账单明细列表--------------------
    交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
    2026-01-05 12:30:00,商户消费,某某餐厅,午餐,支出,¥32.50,零钱,支付成功,4200001111111111,merchant1,/
    2026-01-06 08:15:00,商户消费,地铁公司,地铁票,支出,¥4.00,零钱通,支付成功,4200002222222222,merchant2,
    2026-01-10 20:00:00,转账,张三,/,收入,¥100.00,零钱,已存入零钱,4200003333333333,merchant3,还钱
    2026-01-15 09:00:00,零钱通转出,零钱通,/,不计收支,¥500.00,零钱通,已转出,4200004444444444,merchant4,
    """
      .trimIndent()

  /** 支付宝账单：全角括号、列更多、第二行日期与时间之间没有空格。 */
  private val alipayBill =
    """
    支付宝交易记录明细查询
    账号:[test@example.com]
    起始日期:[2026-01-01 00:00:00]    终止日期:[2026-01-31 23:59:59]
    ---------------------------------交易记录明细列表------------------------------------
    交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态
    2026010522001111111,T200P111,2026-01-05 13:00:00,2026-01-05 13:00:05,2026-01-05 13:00:05,支付宝网站,即时到账,某某超市,日用品,50.00,支出,交易成功,0.00,0.00,,已支出
    2026010622002222222,T200P222,2026-01-0612:30:00,2026-01-06 12:30:05,2026-01-06 12:30:05,支付宝网站,即时到账,某某公司,工资,8000.00,收入,交易成功,0.00,0.00,,已收入
    """
      .trimIndent()

  // ---------- 微信账单 ----------

  @Test
  fun wechat_bill_is_recognized_despite_the_preamble() {
    val result = BillImporter.parseText(wechatBill, zone)
    assertTrue(result is ImportParseResult.Ok)

    val ok = result as ImportParseResult.Ok
    assertEquals(BillFormat.WECHAT, ok.format)
    assertEquals("4 行里应有 1 行因「不计收支」被跳过", 1, ok.skippedRows)
    assertEquals(3, ok.entries.size)
  }

  @Test
  fun wechat_expense_is_parsed_with_amount_and_category_hint() {
    val ok = BillImporter.parseText(wechatBill, zone) as ImportParseResult.Ok
    val lunch = ok.entries.first { it.counterparty == "某某餐厅" }

    assertEquals(TransactionType.EXPENSE, lunch.type)
    assertEquals(3250L, lunch.amountMinor)
    assertEquals("午餐", lunch.product)
    assertEquals("午餐", lunch.note)
    assertEquals(at(2026, 1, 5, 12, 30), lunch.occurredAt)
    assertEquals("午餐应被猜成餐饮", "food", lunch.suggestedIconKey)
    assertEquals("WECHAT:4200001111111111", lunch.sourceId)
  }

  @Test
  fun wechat_placeholder_slash_is_not_treated_as_a_note() {
    val ok = BillImporter.parseText(wechatBill, zone) as ImportParseResult.Ok
    val transfer = ok.entries.first { it.counterparty == "张三" }

    assertNull("商品是「/」这种占位符时应视为空", transfer.product)
    assertEquals("商品为空时回退到备注", "还钱", transfer.note)
  }

  @Test
  fun wechat_income_row_is_parsed() {
    val ok = BillImporter.parseText(wechatBill, zone) as ImportParseResult.Ok
    val transfer = ok.entries.first { it.counterparty == "张三" }

    assertEquals(TransactionType.INCOME, transfer.type)
    assertEquals(10_000L, transfer.amountMinor)
  }

  @Test
  fun transport_keyword_is_guessed() {
    val ok = BillImporter.parseText(wechatBill, zone) as ImportParseResult.Ok
    val metro = ok.entries.first { it.counterparty == "地铁公司" }
    assertEquals("transport", metro.suggestedIconKey)
  }

  @Test
  fun unmatched_entries_are_reported_for_the_user_to_assign() {
    val ok = BillImporter.parseText(wechatBill, zone) as ImportParseResult.Ok
    assertTrue("「还钱」不属于任何收入关键字，应算未匹配", ok.unmatchedCount >= 1)
  }

  // ---------- 支付宝账单 ----------

  @Test
  fun alipay_bill_is_recognized_with_fullwidth_parentheses() {
    val result = BillImporter.parseText(alipayBill, zone)
    assertTrue(result is ImportParseResult.Ok)

    val ok = result as ImportParseResult.Ok
    assertEquals(BillFormat.ALIPAY, ok.format)
    assertEquals(2, ok.entries.size)
    assertEquals(0, ok.skippedRows)
  }

  @Test
  fun alipay_expense_is_parsed_and_categorized() {
    val ok = BillImporter.parseText(alipayBill, zone) as ImportParseResult.Ok
    val groceries = ok.entries.first { it.counterparty == "某某超市" }

    assertEquals(TransactionType.EXPENSE, groceries.type)
    assertEquals(5000L, groceries.amountMinor)
    assertEquals("日用品", groceries.note)
    assertEquals("shopping", groceries.suggestedIconKey)
    assertEquals("ALIPAY:2026010522001111111", groceries.sourceId)
  }

  @Test
  fun alipay_salary_income_is_categorized() {
    val ok = BillImporter.parseText(alipayBill, zone) as ImportParseResult.Ok
    val salary = ok.entries.first { it.counterparty == "某某公司" }

    assertEquals(TransactionType.INCOME, salary.type)
    assertEquals(800_000L, salary.amountMinor)
    assertEquals("salary", salary.suggestedIconKey)
  }

  @Test
  fun date_without_space_before_time_is_parsed() {
    // 支付宝会出现 "2026-01-0612:30:00" 这种没有分隔空格的写法
    val ok = BillImporter.parseText(alipayBill, zone) as ImportParseResult.Ok
    val salary = ok.entries.first { it.counterparty == "某某公司" }
    assertEquals(at(2026, 1, 6, 12, 30), salary.occurredAt)
  }

  // ---------- 编码 ----------

  @Test
  fun gbk_encoded_alipay_bill_is_imported_correctly() {
    assumeTrue("运行环境需支持 GBK", TextDecoding.isGbkAvailable())

    val result = BillImporter.parse( alipayBill.toByteArray(Charset.forName("GBK")), zone)

    assertTrue(result is ImportParseResult.Ok)
    val ok = result as ImportParseResult.Ok
    assertEquals(BillFormat.ALIPAY, ok.format)
    assertEquals(2, ok.entries.size)
    assertTrue("中文必须还原正确，而不是乱码", ok.entries.any { it.counterparty == "某某超市" })
  }

  @Test
  fun utf8_bom_does_not_break_header_detection() {
    val bytes =
      byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + wechatBill.toByteArray(Charsets.UTF_8)

    val result = BillImporter.parse(bytes, zone)

    assertTrue(result is ImportParseResult.Ok)
    assertEquals(BillFormat.WECHAT, (result as ImportParseResult.Ok).format)
  }

  // ---------- 通用 CSV ----------

  @Test
  fun generic_csv_uses_the_amount_sign_when_there_is_no_direction_column() {
    val generic =
      """
      日期,摘要,金额
      2026-02-01,买菜,-120.50
      2026-02-02,退款,+30.00
      """
        .trimIndent()

    val ok = BillImporter.parseText(generic, zone) as ImportParseResult.Ok

    assertEquals(BillFormat.GENERIC, ok.format)
    assertEquals(2, ok.entries.size)
    assertEquals(TransactionType.EXPENSE, ok.entries[0].type)
    assertEquals(12_050L, ok.entries[0].amountMinor)
    assertEquals(TransactionType.INCOME, ok.entries[1].type)
    assertEquals(3_000L, ok.entries[1].amountMinor)
  }

  // ---------- 拒绝与跳过 ----------

  @Test
  fun file_without_a_recognizable_header_is_rejected_with_a_reason() {
    val result = BillImporter.parseText("这不是账单文件\n只有两行文字", zone)
    assertTrue(result is ImportParseResult.Unrecognized)
    assertTrue((result as ImportParseResult.Unrecognized).reason.contains("表头"))
  }

  @Test
  fun empty_file_is_rejected() {
    assertTrue(BillImporter.parseText("", zone) is ImportParseResult.Unrecognized)
    assertTrue(BillImporter.parseText("\n\n\n", zone) is ImportParseResult.Unrecognized)
  }

  @Test
  fun header_without_amount_column_is_rejected() {
    val noAmount =
      """
      交易时间,交易对方,收/支
      2026-01-05,某某餐厅,支出
      """
        .trimIndent()

    val result = BillImporter.parseText(noAmount, zone)
    assertTrue(result is ImportParseResult.Unrecognized)
    assertTrue((result as ImportParseResult.Unrecognized).reason.contains("金额"))
  }

  @Test
  fun closed_transactions_are_skipped() {
    val withClosed =
      """
      交易时间,交易对方,商品,收/支,金额(元),当前状态,交易单号
      2026-01-05 10:00:00,商家A,商品A,支出,10.00,支付成功,ORDER1
      2026-01-05 11:00:00,商家B,商品B,支出,20.00,交易关闭,ORDER2
      2026-01-05 12:00:00,商家C,商品C,支出,30.00,已撤销,ORDER3
      """
        .trimIndent()

    val ok = BillImporter.parseText(withClosed, zone) as ImportParseResult.Ok
    assertEquals(1, ok.entries.size)
    assertEquals(2, ok.skippedRows)
  }

  @Test
  fun refund_rows_are_kept_because_they_are_real_income() {
    // 账单里退款是一条独立的收入行，按状态过滤掉它会丢钱
    val withRefund =
      """
      交易时间,交易对方,商品,收/支,金额(元),当前状态,交易单号
      2026-01-05 10:00:00,商家A,商品A,收入,10.00,已全额退款,ORDER9
      """
        .trimIndent()

    val ok = BillImporter.parseText(withRefund, zone) as ImportParseResult.Ok
    assertEquals(1, ok.entries.size)
    assertEquals(TransactionType.INCOME, ok.entries.single().type)
  }

  @Test
  fun rows_with_unparsable_amount_or_time_are_skipped_not_crashed() {
    val messy =
      """
      交易时间,交易对方,收/支,金额(元),交易单号
      2026-01-05 10:00:00,商家A,支出,10.00,ORDER1
      坏掉的时间,商家B,支出,20.00,ORDER2
      2026-01-05 12:00:00,商家C,支出,不是数字,ORDER3
      2026-01-05 13:00:00,商家D,支出,30.00,ORDER4
      """
        .trimIndent()

    val ok = BillImporter.parseText(messy, zone) as ImportParseResult.Ok
    assertEquals(2, ok.entries.size)
    assertEquals(2, ok.skippedRows)
  }

  @Test
  fun all_rows_invalid_reports_unrecognized_rather_than_silent_success() {
    val allBad =
      """
      交易时间,交易对方,收/支,金额(元)
      坏时间,商家A,支出,10.00
      """
        .trimIndent()

    val result = BillImporter.parseText(allBad, zone)
    assertTrue(
      "一条都没解析出来时必须报错，否则用户以为导入成功了",
      result is ImportParseResult.Unrecognized,
    )
  }

  // ---------- 字段解析细节 ----------

  @Test
  fun amount_parsing_handles_currency_symbols_and_thousand_separators() {
    assertEquals(3250L, BillImporter.parseAmountMinor("¥32.50"))
    assertEquals(3250L, BillImporter.parseAmountMinor("￥32.50"))
    assertEquals(123_456_789L, BillImporter.parseAmountMinor("1,234,567.89"))
    assertEquals(-3250L, BillImporter.parseAmountMinor("-32.50"))
    assertEquals(3200L, BillImporter.parseAmountMinor(" 32 "))
    assertNull(BillImporter.parseAmountMinor(""))
    assertNull(BillImporter.parseAmountMinor("abc"))
  }

  @Test
  fun placeholder_text_is_normalized_to_null() {
    assertNull(BillImporter.cleanText("/"))
    assertNull(BillImporter.cleanText("-"))
    assertNull(BillImporter.cleanText("无"))
    assertNull(BillImporter.cleanText("   "))
    assertEquals("午餐", BillImporter.cleanText(" 午餐 "))
  }

  @Test
  fun header_normalization_folds_fullwidth_parentheses_and_spaces() {
    assertEquals("金额(元)", BillImporter.normalizeHeader("金额（元）"))
    assertEquals("交易时间", BillImporter.normalizeHeader(" 交易 时间 "))
  }

  @Test
  fun entry_without_order_id_still_gets_a_deterministic_source_id() {
    val noOrderId =
      """
      交易时间,交易对方,收/支,金额(元)
      2026-01-05 10:00:00,商家A,支出,10.00
      """
        .trimIndent()

    val first = (BillImporter.parseText(noOrderId, zone) as ImportParseResult.Ok).entries.single()
    val second = (BillImporter.parseText(noOrderId, zone) as ImportParseResult.Ok).entries.single()

    assertEquals("没有单号时也必须稳定，否则重复导入挡不住", first.sourceId, second.sourceId)
    assertTrue(first.sourceId.startsWith("GENERIC:"))
  }
}
