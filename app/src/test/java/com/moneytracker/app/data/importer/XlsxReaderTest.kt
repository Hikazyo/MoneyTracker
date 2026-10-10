package com.moneytracker.app.data.importer

import com.moneytracker.app.data.importer.XlsxFixture.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * xlsx 读取器测试。
 *
 * 重点全部压在「读出来的二维数组有没有错位」上——一旦行列错位，
 * 上层拿到的是金额进了备注列这种**看起来正常、实际全错**的数据，比直接失败更难发现。
 */
class XlsxReaderTest {

  private fun read(rows: List<List<Cell>>): List<List<String>>? =
    XlsxReader.read(XlsxFixture.xlsx(rows))

  private fun textRow(vararg values: String): List<Cell> = values.map { Cell.Text(it) }

  // ---------- 坐标落位 ----------

  @Test
  fun single_cell_row_keeps_its_original_width() {
    // 账单开头的说明行只有 A 列。若实现把每行补成固定宽度，
    // BillImporter 判定表头的「至少 2 格」规则就会失效，说明行会被误判为表头。
    val rows = read(listOf(textRow("微信支付账单明细"), textRow("微信昵称：[测试用户]")))!!

    assertEquals(1, rows[0].size)
    assertEquals("微信支付账单明细", rows[0][0])
    assertEquals(1, rows[1].size)
  }

  @Test
  fun missing_middle_cell_does_not_shift_following_columns() {
    // A 有值、B 缺失、C 有值。按出现顺序追加会读成 ["a","c"]（C 的内容跑到 B 列），
    // 只有按 r="A1"/"C1" 的坐标落位才能得到 ["a","","c"]。
    val rows = read(listOf(listOf(Cell.Text("a"), Cell.Blank, Cell.Text("c"))))!!

    assertEquals(listOf("a", "", "c"), rows[0])
  }

  @Test
  fun trailing_blank_cells_are_trimmed() {
    val rows = read(listOf(listOf(Cell.Text("a"), Cell.Text("b"), Cell.Blank, Cell.Blank)))!!

    assertEquals(listOf("a", "b"), rows[0])
  }

  @Test
  fun blank_cells_inside_the_used_range_are_kept_as_empty_strings() {
    val rows = read(listOf(listOf(Cell.Text("a"), Cell.Blank, Cell.Text("c"), Cell.Blank)))!!

    assertEquals(3, rows[0].size)
    assertEquals("", rows[0][1])
  }

  @Test
  fun far_right_column_is_placed_by_its_letter() {
    // J 列（下标 9）
    val cells = List(9) { Cell.Blank } + listOf(Cell.Text("第十列"))
    val rows = read(listOf(cells))!!

    assertEquals(10, rows[0].size)
    assertEquals("第十列", rows[0][9])
  }

  // ---------- 单元格类型 ----------

  @Test
  fun shared_strings_are_resolved() {
    // XlsxFixture 会把第一条共享字符串拆成两个 <r><t>，顺便验证多段拼接
    val rows = read(listOf(listOf(Cell.Text("零钱通转出"), Cell.Text("已转出"))))!!

    assertEquals(listOf("零钱通转出", "已转出"), rows[0])
  }

  @Test
  fun inline_strings_are_resolved() {
    val rows = read(listOf(listOf(Cell.Inline("内联文本"), Cell.Inline("第二格"))))!!

    assertEquals(listOf("内联文本", "第二格"), rows[0])
  }

  @Test
  fun numeric_cells_are_converted_from_scientific_notation() {
    // Excel 可能写出 2.3E+1；直接当字符串会让金额解析失败
    val rows = read(listOf(listOf(Cell.Number("2.3E+1"), Cell.Number("100"))))!!

    assertEquals(listOf("23", "100"), rows[0])
  }

  @Test
  fun numeric_cell_keeps_the_fraction_that_encodes_time_of_day() {
    // 日期序列号的小数部分就是时刻。若被截断或过度规范化，时间会变 00:00
    val rows = read(listOf(listOf(Cell.Number("46027.520833333336"))))!!

    assertEquals("46027.520833333336", rows[0][0])
  }

  @Test
  fun empty_rows_gaps_do_not_produce_phantom_rows() {
    val sheet =
      XlsxFixture.sheetXml(
        rows = emptyList(),
        sharedIndex = { 0 },
      )
    // 手写一个含跳行（r=1 之后直接 r=4）的表
    val xml =
      sheet.replace(
        "<sheetData></sheetData>",
        """<sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>一</t></is></c></row>""" +
          """<row r="4"><c r="A4" t="inlineStr"><is><t>四</t></is></c></row></sheetData>""",
      )
    val bytes =
      XlsxFixture.zip(
        mapOf(
          "xl/workbook.xml" to XlsxFixture.workbookXml("xl/worksheets/sheet1.xml"),
          "xl/_rels/workbook.xml.rels" to XlsxFixture.relsXml("xl/worksheets/sheet1.xml"),
          "xl/worksheets/sheet1.xml" to xml,
        )
      )

    val rows = XlsxReader.read(bytes)!!

    // 中间的空行被丢掉，但两行的内容都不能串位
    assertEquals(listOf(listOf("一"), listOf("四")), rows)
  }

  // ---------- 工作表定位 ----------

  @Test
  fun first_sheet_is_resolved_through_rels_not_by_filename_order() {
    // 工作表未必叫 sheet1.xml，且 sheet1.xml 可能是别的内容。
    // 只按文件名挑会读到诱饵表。
    // 这里用手工拼的 ZIP（不含共享字符串表），因此单元格用内联字符串。
    val decoy = XlsxFixture.sheetXml(listOf(listOf(Cell.Inline("诱饵"))), { 0 })
    val real = XlsxFixture.sheetXml(listOf(listOf(Cell.Inline("真表"))), { 0 })
    val bytes =
      XlsxFixture.zip(
        mapOf(
          "xl/workbook.xml" to XlsxFixture.workbookXml("xl/worksheets/data.xml"),
          "xl/_rels/workbook.xml.rels" to XlsxFixture.relsXml("xl/worksheets/data.xml"),
          "xl/worksheets/sheet1.xml" to decoy,
          "xl/worksheets/data.xml" to real,
        )
      )

    assertEquals(listOf(listOf("真表")), XlsxReader.read(bytes))
  }

  @Test
  fun zip_without_a_worksheet_reads_as_null() {
    assertNull(XlsxReader.read(XlsxFixture.zipWithoutWorksheet()))
  }

  @Test
  fun corrupt_sheet_xml_reads_as_null_instead_of_throwing() {
    val bytes =
      XlsxFixture.zip(
        mapOf(
          "xl/workbook.xml" to XlsxFixture.workbookXml("xl/worksheets/sheet1.xml"),
          "xl/_rels/workbook.xml.rels" to XlsxFixture.relsXml("xl/worksheets/sheet1.xml"),
          "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row r=\"1\">",
        )
      )

    assertNull(XlsxReader.read(bytes))
  }

  // ---------- 文件头判定 ----------

  @Test
  fun csv_text_is_not_detected_as_zip() {
    assertFalse(XlsxReader.isZip("交易时间,金额(元)\n".toByteArray(Charsets.UTF_8)))
  }

  @Test
  fun xlsx_bytes_are_detected_as_zip() {
    assertTrue(XlsxReader.isZip(XlsxFixture.xlsx(listOf(textRow("a")))))
  }

  // ---------- 辅助函数 ----------

  @Test
  fun column_reference_is_converted_to_index() {
    assertEquals(0, XlsxReader.columnIndex("A1"))
    assertEquals(25, XlsxReader.columnIndex("Z9"))
    assertEquals(26, XlsxReader.columnIndex("AA1"))
    assertEquals(27, XlsxReader.columnIndex("AB100"))
    assertEquals(51, XlsxReader.columnIndex("AZ1"))
    assertEquals(52, XlsxReader.columnIndex("BA1"))
  }

  @Test
  fun malformed_column_reference_is_rejected() {
    assertEquals(-1, XlsxReader.columnIndex(""))
    assertEquals(-1, XlsxReader.columnIndex("1"))
    // XFD 是 Excel 的最后一列（16384），超出我们愿意处理的列数上限
    assertEquals(-1, XlsxReader.columnIndex("XFD1"))
  }

  @Test
  fun numbers_are_normalized_without_losing_precision() {
    assertEquals("23", XlsxReader.normalizeNumber("23.00"))
    assertEquals("23", XlsxReader.normalizeNumber("2.3E+1"))
    assertEquals("100", XlsxReader.normalizeNumber("1E+2"))
    assertEquals("3250", XlsxReader.normalizeNumber("3250"))
    assertEquals("46027.520833333336", XlsxReader.normalizeNumber("46027.520833333336"))
    assertEquals("", XlsxReader.normalizeNumber("  "))
    // 不是数字就原样返回，交给上层报错
    assertEquals("¥32.50", XlsxReader.normalizeNumber("¥32.50"))
  }
}
