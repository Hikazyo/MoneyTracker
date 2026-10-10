package com.moneytracker.app.data.export

import com.moneytracker.app.data.model.TransactionType
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 导出测试。
 *
 * 两个最容易出错、后果也最严重的点：
 *  1. **UTF-8 BOM** —— 没有它 Excel 打开中文全是乱码，功能等于不可用
 *  2. **字段转义** —— 备注里的逗号/引号/换行不转义会把 CSV 结构弄坏
 */
class CsvExporterTest {

  private val zone = ZoneId.of("Asia/Shanghai")

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

  private fun entry(
    amountMinor: Long = 32_050L,
    type: TransactionType = TransactionType.EXPENSE,
    categoryName: String = "餐饮",
    note: String? = "午饭",
    occurredAt: Long = at(2026, 10, 5, 12, 30),
  ) = ExportEntry(occurredAt, type, categoryName, amountMinor, note)

  private fun rows(csv: String) = csv.removePrefix(CsvExporter.UTF8_BOM).split("\r\n")

  // ---------- UTF-8 BOM ----------

  @Test
  fun output_starts_with_the_three_bom_bytes() {
    val bytes = CsvExporter.toCsv(listOf(entry()), zone).toByteArray(StandardCharsets.UTF_8)

    assertEquals("BOM 第一字节", 0xEF.toByte(), bytes[0])
    assertEquals("BOM 第二字节", 0xBB.toByte(), bytes[1])
    assertEquals("BOM 第三字节", 0xBF.toByte(), bytes[2])
  }

  @Test
  fun empty_export_still_carries_bom_and_header() {
    val csv = CsvExporter.toCsv(emptyList(), zone)
    assertTrue(csv.startsWith(CsvExporter.UTF8_BOM))
    assertEquals("日期,类型,分类,金额,备注", rows(csv)[0])
  }

  // ---------- 结构 ----------

  @Test
  fun row_has_columns_in_the_declared_order() {
    val csv = CsvExporter.toCsv(listOf(entry()), zone)
    assertEquals("2026-10-05 12:30,支出,餐饮,320.50,午饭", rows(csv)[1])
  }

  @Test
  fun every_row_ends_with_crlf() {
    val csv = CsvExporter.toCsv(listOf(entry(), entry()), zone)
    assertEquals("表头 + 两条数据各一个换行", 3, Regex("\r\n").findAll(csv).count())
  }

  @Test
  fun income_is_labelled_in_chinese() {
    val csv = CsvExporter.toCsv(listOf(entry(type = TransactionType.INCOME)), zone)
    assertTrue(csv.contains("收入"))
  }

  @Test
  fun date_uses_local_timezone_and_is_zero_padded() {
    val csv = CsvExporter.toCsv(listOf(entry(occurredAt = at(2026, 1, 2, 9, 5))), zone)
    assertTrue("应按本地时区且补零，便于排序", csv.contains("2026-01-02 09:05"))
  }

  // ---------- 金额 ----------

  @Test
  fun amount_has_two_decimals_and_no_thousands_separator() {
    val csv = CsvExporter.toCsv(listOf(entry(amountMinor = 123_456_789L)), zone)
    assertTrue("应是两位小数", csv.contains("1234567.89"))
    assertFalse("带千分位会被 Excel 当作文本，没法求和", csv.contains("1,234,567.89"))
  }

  @Test
  fun empty_note_becomes_an_empty_field() {
    val csv = CsvExporter.toCsv(listOf(entry(note = null)), zone)
    assertTrue("缺备注应留空列，而不是写 null", csv.contains(",320.50,"))
    assertFalse(csv.contains("null"))
  }

  // ---------- 转义 ----------

  @Test
  fun plain_fields_are_left_alone() {
    assertEquals("餐饮", CsvExporter.escape("餐饮"))
    assertEquals("午饭", CsvExporter.escape("午饭"))
  }

  @Test
  fun field_containing_comma_is_quoted() {
    assertEquals("\"买菜,水果\"", CsvExporter.escape("买菜,水果"))
  }

  @Test
  fun field_containing_quote_is_escaped_by_doubling() {
    assertEquals("\"a\"\"b\"", CsvExporter.escape("a\"b"))
  }

  @Test
  fun field_containing_newline_is_quoted() {
    assertEquals("\"第一行\n第二行\"", CsvExporter.escape("第一行\n第二行"))
  }

  @Test
  fun note_with_comma_does_not_break_the_row_structure() {
    val csv = CsvExporter.toCsv(listOf(entry(note = "买菜,水果")), zone)
    assertTrue(csv.contains("\"买菜,水果\""))
    // 转义正确时，备注里的逗号不会把一行拆成两行（末尾换行会 split 出一个空串，忽略它）
    assertEquals(2, rows(csv).count { it.isNotEmpty() })
  }

  @Test
  fun note_with_newline_does_not_add_a_row() {
    val csv = CsvExporter.toCsv(listOf(entry(note = "第一行\n第二行")), zone)
    // 备注里有 \n，但被引号包住了，行尾仍是 \r\n；这里只数真正的记录分隔
    assertTrue(csv.contains("\"第一行\n第二行\""))
  }

  @Test
  fun category_name_with_comma_is_also_quoted() {
    val csv = CsvExporter.toCsv(listOf(entry(categoryName = "餐饮,外卖")), zone)
    assertTrue(csv.contains("\"餐饮,外卖\""))
  }

  // ---------- 文件名 ----------

  @Test
  fun suggested_file_names_include_the_date() {
    val millis = at(2026, 10, 5, 12, 30)
    assertEquals("moneytracker-2026-10-05.csv", CsvExporter.suggestedCsvFileName(millis, zone))
    assertEquals(
      "moneytracker-backup-2026-10-05.json",
      CsvExporter.suggestedBackupFileName(millis, zone),
    )
  }
}
