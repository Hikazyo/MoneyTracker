package com.moneytracker.app.data.export

import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.model.TransactionType
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 导出一行所需的字段。与数据库实体解耦，便于直接单元测试。 */
data class ExportEntry(
  val occurredAt: Long,
  val type: TransactionType,
  val categoryName: String,
  val amountMinor: Long,
  val note: String?,
)

/**
 * CSV 导出。纯函数，不碰文件系统——写到哪里由上层通过 SAF 决定。
 */
object CsvExporter {

  /**
   * UTF-8 BOM。
   *
   * **这一行决定了导出功能是否真的「能用」**：不写 BOM，Excel 会按本地代码页解释文件，
   * 中文全部变成乱码。用户拿到一个乱码的 CSV，等同于功能是坏的。
   */
  const val UTF8_BOM = "\uFEFF"

  /** 行尾用 CRLF：Excel 对此最友好。 */
  private const val LINE_BREAK = "\r\n"

  val HEADER = listOf("日期", "类型", "分类", "金额", "备注")

  fun toCsv(entries: List<ExportEntry>, zone: ZoneId): String = buildString {
    append(UTF8_BOM)
    append(HEADER.joinToString(","))
    append(LINE_BREAK)
    entries.forEach { entry ->
      append(
        listOf(
            formatDateTime(entry.occurredAt, zone),
            typeLabel(entry.type),
            entry.categoryName,
            // 不带千分位：带了 Excel 会把它当文本而不是数字，后续没法求和
            Money.format(entry.amountMinor),
            entry.note.orEmpty(),
          )
          .joinToString(",") { escape(it) }
      )
      append(LINE_BREAK)
    }
  }

  /**
   * CSV 字段转义。
   *
   * 备注里出现逗号、引号或换行都很正常（比如「买菜,水果」），
   * 不转义会把整行的字段结构弄乱，甚至把一行拆成两行。
   */
  fun escape(value: String): String {
    val needsQuoting =
      value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
    if (!needsQuoting) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
  }

  fun typeLabel(type: TransactionType): String =
    if (type == TransactionType.EXPENSE) "支出" else "收入"

  private fun formatDateTime(epochMillis: Long, zone: ZoneId): String {
    val dt = Instant.ofEpochMilli(epochMillis).atZone(zone)
    return String.format(
      Locale.US,
      "%04d-%02d-%02d %02d:%02d",
      dt.year,
      dt.monthValue,
      dt.dayOfMonth,
      dt.hour,
      dt.minute,
    )
  }

  /** 建议文件名，如 moneytracker-2026-10-10.csv */
  fun suggestedCsvFileName(epochMillis: Long, zone: ZoneId): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    return String.format(
      Locale.US,
      "moneytracker-%04d-%02d-%02d.csv",
      date.year,
      date.monthValue,
      date.dayOfMonth,
    )
  }

  /** 建议文件名，如 moneytracker-backup-2026-10-10.json */
  fun suggestedBackupFileName(epochMillis: Long, zone: ZoneId): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    return String.format(
      Locale.US,
      "moneytracker-backup-%04d-%02d-%02d.json",
      date.year,
      date.monthValue,
      date.dayOfMonth,
    )
  }
}
