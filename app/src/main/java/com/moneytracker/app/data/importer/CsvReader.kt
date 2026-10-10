package com.moneytracker.app.data.importer

/**
 * 极简 CSV 读取器（RFC 4180 风格）。
 *
 * 不能用 `split(",")`：备注里出现逗号是常态，而且字段可能是被引号包起来的、
 * 甚至引号里还有换行。那样会直接把一行拆错、把字段错位。
 *
 * 支持：
 *  - 双引号包裹的字段
 *  - 引号内的 `""` 表示一个字面引号
 *  - 引号内的逗号与换行
 *  - CRLF / LF / CR 三种行尾
 */
object CsvReader {

  /**
   * 行数上限与单行字段数上限。
   *
   * 文件整体大小已由 `DocumentStore` 卡在 32 MB，但那仍然允许构造出
   * 「一行塞进几百万个逗号」这种输入：字段数不受控时，光是把一行切开就能把内存吃光。
   * 量级与 `XlsxReader` 的行列上限保持一致。
   */
  private const val MAX_ROWS = 200_000
  private const val MAX_FIELDS_PER_ROW = 1_024

  fun read(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var index = 0

    fun endField() {
      // 超出字段上限时丢弃多余字段而不是抛错：前面的列仍然可能可用，
      // 而上层是按列名取值的，多出来的尾部字段本来也不参与匹配
      if (row.size < MAX_FIELDS_PER_ROW) row.add(field.toString())
      field.clear()
    }

    fun endRow() {
      endField()
      if (rows.size < MAX_ROWS) rows.add(row)
      row = mutableListOf()
    }

    while (index < text.length) {
      val c = text[index]
      when {
        inQuotes -> {
          when {
            c == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
              field.append('"')
              index++
            }
            c == '"' -> inQuotes = false
            else -> field.append(c)
          }
        }

        c == '"' -> inQuotes = true

        c == ',' -> endField()

        c == '\r' -> {
          // 吞掉 CRLF 里的 LF，避免多出一个空行
          if (index + 1 < text.length && text[index + 1] == '\n') index++
          endRow()
        }

        c == '\n' -> endRow()

        else -> field.append(c)
      }
      index++
    }

    // 末行可能没有换行结尾
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()

    // 去掉完全空白的行（账单文件里常见）
    return rows.filter { r -> r.any { it.isNotBlank() } }
  }
}
