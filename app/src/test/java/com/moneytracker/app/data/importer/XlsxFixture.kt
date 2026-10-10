package com.moneytracker.app.data.importer

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 在内存里拼一个**真的** xlsx。
 *
 * 不用第三方库，也不塞二进制样本文件：xlsx 就是「ZIP + 几个 XML」，
 * 手工拼反而能精确控制最容易读错的地方——共享字符串、内联字符串、数值单元格、
 * 跳格（中间列缺失）、以及工作表未必叫 sheet1.xml。
 *
 * 样本文件还有个缺点：二进制 diff 不可读，出问题时看不出哪里变了。
 */
object XlsxFixture {

  /** 一个单元格的形态。刻意区分类型，因为读错类型正是 xlsx 解析的主要风险。 */
  sealed interface Cell {
    /** 共享字符串表引用（`t="s"`），真实 xlsx 里绝大多数字符串都是这种。 */
    data class Text(val value: String) : Cell

    /** 数值单元格（无 `t` 属性）。日期序列号与纯数字金额都是这种。 */
    data class Number(val value: String) : Cell

    /** 内联字符串（`t="inlineStr"`）。 */
    data class Inline(val value: String) : Cell

    /** 该格不存在——用来构造「中间列缺失」，检验是否按坐标落位。 */
    object Blank : Cell
  }

  /**
   * 常用入口：一行一个 `List<Cell>`，行号从 1 开始。
   *
   * 刻意**先写工作表、后写共享字符串表**：真实文件里两者顺序不定，
   * 而读取实现必须应对「工作表先出现、但解析它时需要共享字符串表」这种情况。
   */
  fun xlsx(rows: List<List<Cell>>, sheetPath: String = "xl/worksheets/sheet1.xml"): ByteArray {
    val shared = mutableListOf<String>()
    fun sharedIndex(value: String): Int {
      val existing = shared.indexOf(value)
      if (existing >= 0) return existing
      shared.add(value)
      return shared.size - 1
    }

    val sheet = sheetXml(rows, ::sharedIndex)
    val entries =
      linkedMapOf(
        "[Content_Types].xml" to CONTENT_TYPES,
        sheetPath to sheet,
        "xl/workbook.xml" to workbookXml(sheetPath),
        "xl/_rels/workbook.xml.rels" to relsXml(sheetPath),
        "xl/sharedStrings.xml" to sharedStringsXml(shared),
      )
    return zip(entries)
  }

  /** 只放一个不是工作表的文件——用来模拟「加密的账单压缩包」。 */
  fun zipWithoutWorksheet(): ByteArray =
    zip(mapOf("readme.txt" to "微信支付账单已加密，请用邮件里的密码解压"))

  /** 直接把给定的条目打成 ZIP。 */
  fun zip(entries: Map<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      entries.forEach { (name, content) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  fun sheetXml(rows: List<List<Cell>>, sharedIndex: (String) -> Int): String =
    buildString {
      append(XML_DECLARATION)
      append(
        """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>"""
      )
      rows.forEachIndexed { rowIndex, cells -> append(rowXml(rowIndex + 1, cells, sharedIndex)) }
      append("</sheetData></worksheet>")
    }

  private fun rowXml(
    rowNumber: Int,
    cells: List<Cell>,
    sharedIndex: (String) -> Int,
  ): String =
    buildString {
      append("""<row r="$rowNumber">""")
      cells.forEachIndexed { columnIndex, cell ->
        // Blank 直接不写 <c>，从而在 XML 里形成真正的跳格
        if (cell !is Cell.Blank) {
          append(cellXml(columnName(columnIndex) + rowNumber, cell, sharedIndex))
        }
      }
      append("</row>")
    }

  private fun cellXml(ref: String, cell: Cell, sharedIndex: (String) -> Int): String =
    when (cell) {
      is Cell.Text -> """<c r="$ref" t="s"><v>${sharedIndex(cell.value)}</v></c>"""
      is Cell.Number -> """<c r="$ref"><v>${cell.value}</v></c>"""
      is Cell.Inline -> """<c r="$ref" t="inlineStr"><is><t>${escape(cell.value)}</t></is></c>"""
      Cell.Blank -> ""
    }

  fun sharedStringsXml(values: List<String>): String =
    buildString {
      append(XML_DECLARATION)
      append(
        """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${values.size}" uniqueCount="${values.size}">"""
      )
      // 真实的 sharedStrings 常常把一条字符串拆成多个 <r><t>，因此第一条模仿这种写法
      values.forEachIndexed { index, value ->
        if (index == 0 && value.isNotEmpty()) {
          append("<si><r><t>${escape(value.substring(0, 1))}</t></r><r><t>${escape(value.substring(1))}</t></r></si>")
        } else {
          append("<si><t>${escape(value)}</t></si>")
        }
      }
      append("</sst>")
    }

  fun workbookXml(sheetPath: String): String =
    XML_DECLARATION +
      """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
      """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
      """<sheets><sheet name="账单明细" sheetId="1" r:id="rId1"/></sheets></workbook>"""

  /** rels 的 Target 是相对 `xl/` 的。 */
  fun relsXml(sheetPath: String): String =
    XML_DECLARATION +
      """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
      """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" """ +
      """Target="${sheetPath.removePrefix("xl/")}"/></Relationships>"""

  /** 列下标 → 列名：0 → A、25 → Z、26 → AA。 */
  fun columnName(index: Int): String {
    var remaining = index
    val out = StringBuilder()
    while (true) {
      out.append(('A' + remaining % 26))
      remaining = remaining / 26 - 1
      if (remaining < 0) break
    }
    return out.reverse().toString()
  }

  private fun escape(raw: String): String =
    raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private const val XML_DECLARATION = """<?xml version="1.0" encoding="UTF-8"?>"""

  private const val CONTENT_TYPES =
    XML_DECLARATION +
      """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
      """<Default Extension="xml" ContentType="application/xml"/>""" +
      """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
      """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
      """</Types>"""
}
