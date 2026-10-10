package com.moneytracker.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.backup.BackupCodec
import com.moneytracker.app.data.backup.BackupFile
import com.moneytracker.app.data.export.CsvExporter
import com.moneytracker.app.data.io.DocumentStore
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.data.repository.RestoreResult
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
  val isBusy: Boolean = false,
  val message: String? = null,
  /** 已解析成功、等待用户确认的备份。恢复是破坏性操作，必须二次确认。 */
  val pendingRestore: BackupFile? = null,
)

class SettingsViewModel(
  private val repository: LedgerRepository,
  private val documents: DocumentStore,
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

  private val _state = MutableStateFlow(SettingsUiState())
  val state: StateFlow<SettingsUiState> = _state.asStateFlow()

  val suggestedCsvName: String get() = CsvExporter.suggestedCsvFileName(clock(), zone)

  val suggestedBackupName: String get() = CsvExporter.suggestedBackupFileName(clock(), zone)

  fun exportCsv(documentId: String) {
    runOperation(success = { count -> "已导出 $count 笔账目" }) {
      val entries = repository.exportEntries()
      documents.writeText(documentId, CsvExporter.toCsv(entries, zone)).getOrThrow()
      entries.size
    }
  }

  fun createBackup(documentId: String) {
    runOperation(success = { count -> "已备份 $count 笔账目" }) {
      val backup = repository.createBackup(clock())
      documents.writeText(documentId, BackupCodec.encode(backup)).getOrThrow()
      backup.transactions.size
    }
  }

  /** 读入并解析备份文件。解析成功只进入待确认状态，不立即写入。 */
  fun loadBackupForRestore(documentId: String) {
    _state.update { it.copy(isBusy = true, message = null) }
    viewModelScope.launch {
      val text =
        documents.readText(documentId).getOrElse { error ->
          _state.update { it.copy(isBusy = false, message = "读取文件失败：${error.message}") }
          return@launch
        }

      // 去掉可能存在的 BOM：某些编辑器保存 UTF-8 时会带上，会让 JSON 解析直接失败
      when (val decoded = BackupCodec.decode(text.removePrefix("\uFEFF"))) {
        is BackupCodec.DecodeResult.Ok ->
          _state.update { it.copy(isBusy = false, pendingRestore = decoded.backup) }

        is BackupCodec.DecodeResult.UnsupportedVersion ->
          _state.update {
            it.copy(
              isBusy = false,
              message =
                "备份版本不兼容（文件为 v${decoded.found}，当前支持 v${decoded.expected}），已取消恢复",
            )
          }

        is BackupCodec.DecodeResult.Malformed ->
          _state.update {
            it.copy(isBusy = false, message = "备份文件无法解析：${decoded.message}")
          }
      }
    }
  }

  fun confirmRestore() {
    val backup = _state.value.pendingRestore ?: return
    _state.update { it.copy(isBusy = true, pendingRestore = null) }
    viewModelScope.launch {
      when (val result = repository.restoreBackup(backup)) {
        is RestoreResult.Success ->
          _state.update {
            it.copy(isBusy = false, message = "已恢复 ${result.transactionCount} 笔账目")
          }

        is RestoreResult.Failure ->
          _state.update {
            // 明确告知「现有数据未改动」：否则用户会以为数据已经被破坏了
            it.copy(isBusy = false, message = "恢复失败，现有数据未改动：${result.message}")
          }
      }
    }
  }

  fun dismissRestore() {
    _state.update { it.copy(pendingRestore = null) }
  }

  fun onMessageShown() {
    _state.update { it.copy(message = null) }
  }

  private fun runOperation(success: (Int) -> String, block: suspend () -> Int) {
    _state.update { it.copy(isBusy = true, message = null) }
    viewModelScope.launch {
      try {
        val count = block()
        _state.update { it.copy(isBusy = false, message = success(count)) }
      } catch (e: Exception) {
        _state.update { it.copy(isBusy = false, message = "操作失败：${e.message}") }
      }
    }
  }
}
