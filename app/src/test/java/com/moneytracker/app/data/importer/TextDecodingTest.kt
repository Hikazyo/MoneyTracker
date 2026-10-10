package com.moneytracker.app.data.importer

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 编码嗅探测试。
 *
 * 这是导入功能能否可用的前提：**微信账单是 UTF-8，支付宝账单是 GBK**。
 * 一律按 UTF-8 解会把支付宝账单变成乱码，连表头都认不出来。
 */
class TextDecodingTest {

  private val chinese = "交易时间,交易对方\n2026-01-05,某某餐厅"

  @Test
  fun utf8_bom_is_detected() {
    val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()
    assertTrue(TextDecoding.hasUtf8Bom(bytes))
  }

  @Test
  fun bom_is_stripped_from_the_decoded_text() {
    val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + chinese.toByteArray(Charsets.UTF_8)
    val decoded = TextDecoding.decode(bytes)

    assertFalse("BOM 不能残留在文本里，否则表头首格会带上一个不可见字符", decoded.startsWith("\uFEFF"))
    assertEquals(chinese, decoded)
  }

  @Test
  fun plain_utf8_is_decoded_as_utf8() {
    assertEquals(chinese, TextDecoding.decode(chinese.toByteArray(Charsets.UTF_8)))
  }

  @Test
  fun ascii_only_content_decodes_fine() {
    val ascii = "time,amount,counterparty"
    assertEquals(ascii, TextDecoding.decode(ascii.toByteArray(Charsets.US_ASCII)))
  }

  @Test
  fun gbk_bytes_are_not_mistaken_for_utf8() {
    assumeTrue("运行环境需支持 GBK", TextDecoding.isGbkAvailable())
    val bytes = chinese.toByteArray(Charset.forName("GBK"))

    // 先确认前提成立：GBK 字节确实不是合法 UTF-8，否则这个测试本身没有意义
    assertFalse(
      "GBK 编码的中文不应是合法 UTF-8",
      runCatching { String(bytes, Charsets.UTF_8).let { it == chinese } }.getOrDefault(false),
    )

    assertEquals("应回落 GBK 并正确还原中文", chinese, TextDecoding.decode(bytes))
  }

  @Test
  fun gbk_encoded_whole_bill_is_decoded() {
    assumeTrue("运行环境需支持 GBK", TextDecoding.isGbkAvailable())
    val bill =
      """
      支付宝交易记录明细查询
      ---------------------------------交易记录明细列表------------------------------------
      交易号,交易对方,商品名称,金额（元）,收/支,交易状态
      2026010522001111,某某超市,日用品,50.00,支出,交易成功
      """
        .trimIndent()

    val decoded = TextDecoding.decode(bill.toByteArray(Charset.forName("GBK")))

    assertTrue(decoded.contains("支付宝交易记录明细查询"))
    assertTrue(decoded.contains("某某超市"))
    assertTrue(decoded.contains("交易成功"))
  }

  @Test
  fun empty_input_decodes_to_empty_string() {
    assertEquals("", TextDecoding.decode(ByteArray(0)))
  }

  @Test
  fun bom_only_input_decodes_to_empty_string() {
    val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    assertEquals("", TextDecoding.decode(bytes))
  }
}
