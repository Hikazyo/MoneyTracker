package com.moneytracker.app.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.importer.BillImporter
import com.moneytracker.app.data.importer.ImportParseResult
import com.moneytracker.app.data.importer.ImportedEntry
import com.moneytracker.app.data.io.DocumentStore
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.repository.LedgerRepository
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 兜底分类的候选项。 */
data class ImportCategoryOption(val id: Long, val name: String, val iconKey: String)

/** 导入前的预览。用户据此确认「会导入什么、多少条没匹配上分类」。 */
data class ImportPreview(
  val formatLabel: String,
  val totalCount: Int,
  val skippedRows: Int,
  val autoMatchedCount: Int,
  val unmatchedExpenseCount: Int,
  val unmatchedIncomeCount: Int,
  val expenseOptions: List<ImportCategoryOption>,
  val incomeOptions: List<ImportCategoryOption>,
  val entries: List<ImportedEntry>,
)

data class ImportUiState(
  val isBusy: Boolean = false,
  val preview: ImportPreview? = null,
  val fallbackExpenseCategoryId: Long? = null,
  val fallbackIncomeCategoryId: Long? = null,
  val message: String? = null,
) {
  /**
   * 只有「所有未匹配分类都有兜底可落」时才允许确认。
   *
   * 支出与收入各需要一个兜底分类：分类是分方向的，一个分类不可能同时承接两个方向。
   */
  val canConfirm: Boolean
    get() {
      val current = preview ?: return false
      val expenseReady = current.unmatchedExpenseCount == 0 || fallbackExpenseCategoryId != null
      val incomeReady = current.unmatchedIncomeCount == 0 || fallbackIncomeCategoryId != null
      return expenseReady && incomeReady
    }
}

class ImportViewModel(
  private val repository: LedgerRepository,
  private val documents: DocumentStore,
  private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

  private val _state = MutableStateFlow(ImportUiState())
  val state: StateFlow<ImportUiState> = _state.asStateFlow()

  /** 读取并解析用户选中的账单文件，成功则进入预览状态。 */
  fun startImport(documentId: String) {
    _state.update { it.copy(isBusy = true, message = null, preview = null) }
    viewModelScope.launch {
      val bytes =
        documents.readBytes(documentId).getOrElse { error ->
          _state.update { it.copy(isBusy = false, message = "读取文件失败：${error.message}") }
          return@launch
        }

      when (val parsed = BillImporter.parse(bytes, zone)) {
        is ImportParseResult.Unrecognized ->
          _state.update { it.copy(isBusy = false, message = parsed.reason) }

        is ImportParseResult.Ok -> {
          val categories = repository.observeCategories().first()
          val knownIconKeys = categories.map { it.iconKey }.toSet()

          // 猜出的 iconKey 可能不在用户当前分类表里（例如该分类已被归档），一并算作未匹配
          val unmatched =
            parsed.entries.filter {
              it.suggestedIconKey == null || it.suggestedIconKey !in knownIconKeys
            }

          val expenseOptions =
            categories.filter { it.type == TransactionType.EXPENSE }.map { it.toOption() }
          val incomeOptions =
            categories.filter { it.type == TransactionType.INCOME }.map { it.toOption() }

          _state.update {
            it.copy(
              isBusy = false,
              preview =
                ImportPreview(
                  formatLabel = parsed.format.displayName,
                  totalCount = parsed.entries.size,
                  skippedRows = parsed.skippedRows,
                  autoMatchedCount = parsed.entries.size - unmatched.size,
                  unmatchedExpenseCount = unmatched.count { e -> e.type == TransactionType.EXPENSE },
                  unmatchedIncomeCount = unmatched.count { e -> e.type == TransactionType.INCOME },
                  expenseOptions = expenseOptions,
                  incomeOptions = incomeOptions,
                  entries = parsed.entries,
                ),
              // 默认选中第一个，省掉一次必然的操作
              fallbackExpenseCategoryId = expenseOptions.firstOrNull()?.id,
              fallbackIncomeCategoryId = incomeOptions.firstOrNull()?.id,
            )
          }
        }
      }
    }
  }

  fun selectFallback(type: TransactionType, categoryId: Long) {
    _state.update {
      if (type == TransactionType.EXPENSE) it.copy(fallbackExpenseCategoryId = categoryId)
      else it.copy(fallbackIncomeCategoryId = categoryId)
    }
  }

  fun confirmImport() {
    val preview = _state.value.preview ?: return
    val expenseFallback = _state.value.fallbackExpenseCategoryId
    val incomeFallback = _state.value.fallbackIncomeCategoryId

    _state.update { it.copy(isBusy = true, preview = null) }
    viewModelScope.launch {
      try {
        // 自定义分类的图标是从同一个图标目录里选的，因此完全可能和某个内置分类撞 key。
        // associate 对重复 key 保留**最后**写入的那个，所以把内置分类排在后面让它胜出——
        // 否则用户随手在自定义分类上选了个 🍚，导入的餐饮账目就会全跑到那个分类里。
        val byIconKey =
          repository
            .observeCategories()
            .first()
            .sortedBy { it.isBuiltIn }
            .associate { it.iconKey to it.id }

        var inserted = 0
        var duplicates = 0

        // 兜底分类按方向分开指定，因此两个方向分别导入
        preview.entries.filter { it.type == TransactionType.EXPENSE }.takeIf {
          it.isNotEmpty() && expenseFallback != null
        }?.let { expenseEntries ->
          val result = repository.importEntries(expenseEntries, byIconKey, expenseFallback!!)
          inserted += result.inserted
          duplicates += result.duplicates
        }

        preview.entries.filter { it.type == TransactionType.INCOME }.takeIf {
          it.isNotEmpty() && incomeFallback != null
        }?.let { incomeEntries ->
          val result = repository.importEntries(incomeEntries, byIconKey, incomeFallback!!)
          inserted += result.inserted
          duplicates += result.duplicates
        }

        _state.update { it.copy(isBusy = false, message = resultMessage(inserted, duplicates)) }
      } catch (e: Exception) {
        _state.update { it.copy(isBusy = false, message = "导入失败：${e.message}") }
      }
    }
  }

  fun dismissPreview() {
    _state.update { it.copy(preview = null) }
  }

  fun onMessageShown() {
    _state.update { it.copy(message = null) }
  }

  private fun resultMessage(inserted: Int, duplicates: Int): String =
    if (duplicates > 0) "已导入 $inserted 条，跳过重复 $duplicates 条" else "已导入 $inserted 条"

  private fun CategoryEntity.toOption() = ImportCategoryOption(id = id, name = name, iconKey = iconKey)
}
