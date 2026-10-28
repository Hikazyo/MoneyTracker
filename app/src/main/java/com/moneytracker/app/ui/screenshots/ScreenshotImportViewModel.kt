package com.moneytracker.app.ui.screenshots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.importer.ImportedEntry
import com.moneytracker.app.data.io.DocumentStore
import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.ocr.ScreenshotOcr
import com.moneytracker.app.data.ocr.ScreenshotParser
import com.moneytracker.app.data.repository.LedgerRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 预览里的一行。
 *
 * 金额**只读**：实测三类来源的金额识别准确率接近 100%，做成可编辑的收益很低；
 * 而日期会错（`昨天` 这类相对日期实测会被识别成乱码），所以日期必须可改。
 */
data class ScreenshotRow(
  val id: String,
  val imageName: String,
  val merchant: String?,
  val categoryLabel: String?,
  val occurredAtMillis: Long,
  val type: TransactionType,
  val amountMinor: Long,
  val categoryId: Long,
  val selected: Boolean,
  /** 疑似资金划转（自动攒、转账、退款…），默认不勾选。 */
  val suspicious: Boolean,
  /** 日期/时间不是这一行读出来的，是兜底推的。 */
  val timeUncertain: Boolean,
  /** 金额没有符号，方向是按平台惯例推的。 */
  val directionUncertain: Boolean,
) {
  val amountText: String get() = Money.formatGrouped(amountMinor)
}

data class ScreenshotImportState(
  val isBusy: Boolean = false,
  val progress: String? = null,
  val rows: List<ScreenshotRow> = emptyList(),
  val skippedRows: Int = 0,
  val message: String? = null,
  val categories: List<CategoryEntity> = emptyList(),
) {
  val selectedCount: Int get() = rows.count { it.selected }

  val hasRows: Boolean get() = rows.isNotEmpty()

  /** 按截图分组，保持识别顺序。 */
  val grouped: List<Pair<String, List<ScreenshotRow>>>
    get() = rows.groupBy { it.imageName }.map { (k, v) -> k to v }

  fun categoriesFor(type: TransactionType): List<CategoryEntity> =
    categories.filter { it.type == type && !it.isArchived }
}

class ScreenshotImportViewModel(
  private val repository: LedgerRepository,
  private val documents: DocumentStore,
  private val recognizer: ScreenshotRecognizer = MlKitScreenshotRecognizer(),
  private val parser: ScreenshotParser = ScreenshotParser(),
  private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

  private val _state = MutableStateFlow(ScreenshotImportState())
  val state: StateFlow<ScreenshotImportState> = _state.asStateFlow()

  /** 识别用户选中的所有截图。任何一张失败都只跳过它，不中断整批。 */
  fun startRecognition(documentIds: List<String>) {
    if (documentIds.isEmpty()) return
    _state.update {
      it.copy(isBusy = true, rows = emptyList(), skippedRows = 0, message = null, progress = null)
    }
    viewModelScope.launch {
      val categories = repository.observeCategories().first()
      val collected = mutableListOf<ScreenshotRow>()
      var skipped = 0
      var failures = 0

      documentIds.forEachIndexed { index, documentId ->
        _state.update { it.copy(progress = "正在识别第 ${index + 1}/${documentIds.size} 张") }

        val bytes = documents.readBytes(documentId).getOrNull()
        if (bytes == null) {
          failures++
          return@forEachIndexed
        }
        val fileName = documents.displayName(documentId)
        val recognized = runCatching { recognizer.recognize(bytes) }.getOrNull()
        if (recognized == null) {
          failures++
          return@forEachIndexed
        }

        val result =
          parser.parse(
            lines = recognized.lines,
            imageWidth = recognized.width,
            imageHeight = recognized.height,
            imageKey = ScreenshotOcr.imageKey(bytes),
            fileName = fileName,
            fallbackDate = LocalDate.now(zone),
          )
        skipped += result.skippedRows
        val label = fileName ?: "第 ${index + 1} 张"
        collected += result.entries.map { it.toRow(categories, label) }
      }

      _state.update {
        it.copy(
          isBusy = false,
          progress = null,
          categories = categories,
          rows = collected,
          skippedRows = skipped,
          message = recognitionMessage(collected.size, skipped, failures),
        )
      }
    }
  }

  fun toggle(rowId: String) {
    _state.update { state ->
      state.copy(
        rows = state.rows.map { if (it.id == rowId) it.copy(selected = !it.selected) else it }
      )
    }
  }

  fun setAllSelected(selected: Boolean) {
    _state.update { state -> state.copy(rows = state.rows.map { it.copy(selected = selected) }) }
  }

  fun setCategory(rowId: String, categoryId: Long) {
    _state.update { state ->
      state.copy(
        rows = state.rows.map { if (it.id == rowId) it.copy(categoryId = categoryId) else it }
      )
    }
  }

  /** 只改日期，保留原时间——用户要修的是「哪天」，不是「几点几分」。 */
  fun setDate(rowId: String, date: LocalDate) {
    _state.update { state ->
      state.copy(
        rows =
          state.rows.map { row ->
            if (row.id != rowId) return@map row
            val existing = Instant.ofEpochMilli(row.occurredAtMillis).atZone(zone).toLocalDateTime()
            val merged =
              existing
                .withYear(date.year)
                .withMonth(date.monthValue)
                .withDayOfMonth(date.dayOfMonth)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            row.copy(occurredAtMillis = merged, timeUncertain = false)
          }
      )
    }
  }

  fun importSelected() {
    val current = _state.value
    val chosen = current.rows.filter { it.selected }
    if (chosen.isEmpty()) {
      _state.update { it.copy(message = "没有勾选任何账目") }
      return
    }

    _state.update { it.copy(isBusy = true) }
    viewModelScope.launch {
      try {
        val categories = repository.observeCategories().first()
        // 与 CSV 导入保持同一套解算：自定义分类的图标可能和内置分类撞 key，
        // 因此把内置分类排在后面，让它在 associate 时胜出。
        val byIconKey = categories.sortedBy { it.isBuiltIn }.associate { it.iconKey to it.id }
        val byId = categories.associateBy { it.id }

        var inserted = 0
        var duplicates = 0

        for (type in TransactionType.entries) {
          val subset = chosen.filter { it.type == type }
          if (subset.isEmpty()) continue
          val fallback =
            categories.filter { it.type == type && !it.isArchived }.minByOrNull { it.sortOrder }
              ?: categories.firstOrNull { it.type == type }
              ?: continue
          val entries =
            subset.map { row ->
              ImportedEntry(
                sourceId = row.id,
                occurredAt = row.occurredAtMillis,
                type = row.type,
                amountMinor = row.amountMinor,
                counterparty = row.merchant,
                product = row.categoryLabel,
                note = null,
                suggestedIconKey = byId[row.categoryId]?.iconKey ?: row.categoryLabel,
              )
            }
          val result = repository.importEntries(entries, byIconKey, fallback.id)
          inserted += result.inserted
          duplicates += result.duplicates
        }

        _state.update {
          it.copy(
            isBusy = false,
            rows = emptyList(),
            message = importMessage(inserted, duplicates),
          )
        }
      } catch (e: Exception) {
        _state.update { it.copy(isBusy = false, message = "导入失败：${e.message}") }
      }
    }
  }

  fun clearRows() {
    _state.update { it.copy(rows = emptyList(), skippedRows = 0) }
  }

  fun onMessageShown() {
    _state.update { it.copy(message = null) }
  }

  override fun onCleared() {
    (recognizer as? AutoCloseable)?.close()
  }

  private fun ScreenshotParser.Entry.toRow(
    categories: List<CategoryEntity>,
    imageLabel: String,
  ): ScreenshotRow {
    val options = categories.filter { it.type == type && !it.isArchived }
    val byIconKey = options.firstOrNull { it.iconKey == suggestedIconKey }
    // 兜底**不能**取列表第一个：排第一的永远是「餐饮」，会让所有没匹配上的行
    // 都变成餐饮，把统计搞脏。明确落到「其他支出 / 其他收入」。
    val fallbackKey =
      if (type == TransactionType.EXPENSE) "other_expense" else "other_income"
    val chosen =
      byIconKey
        ?: options.firstOrNull { it.iconKey == fallbackKey }
        ?: options.firstOrNull()
    return ScreenshotRow(
      id = sourceId,
      imageName = imageLabel,
      merchant = merchant,
      categoryLabel = categoryLabel,
      occurredAtMillis = occurredAtMillis,
      type = type,
      amountMinor = amountMinor,
      categoryId = chosen?.id ?: 0L,
      // 疑似资金划转默认不勾选：宁可少记一笔让用户手动勾，也不要悄悄虚增支出
      selected = !suspicious,
      suspicious = suspicious,
      timeUncertain = timeUncertain,
      directionUncertain = directionUncertain,
    )
  }

  private fun recognitionMessage(rows: Int, skipped: Int, failures: Int): String? {
    if (rows == 0 && failures == 0) return "没有识别出任何账目，换一张试试"
    val parts = mutableListOf<String>()
    if (rows > 0) parts += "识别出 $rows 笔"
    if (skipped > 0) parts += "跳过 $skipped 行（金额被裁切）"
    if (failures > 0) parts += "$failures 张图无法识别"
    return if (parts.isEmpty()) null else parts.joinToString("，")
  }

  private fun importMessage(inserted: Int, duplicates: Int): String =
    if (duplicates > 0) "已导入 $inserted 条，跳过重复 $duplicates 条" else "已导入 $inserted 条"
}
