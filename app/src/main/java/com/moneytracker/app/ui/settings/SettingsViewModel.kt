package com.moneytracker.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.backup.BackupCodec
import com.moneytracker.app.data.backup.BackupFile
import com.moneytracker.app.data.export.CsvExporter
import com.moneytracker.app.data.io.AndroidDocumentStore
import com.moneytracker.app.data.io.DocumentStore
import com.moneytracker.app.data.io.DocumentTooLargeException
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
  /**
   * 选中的是一份加密备份，正在等用户输入口令。
   *
   * 存的是已经读进内存的文件正文，这样用户输错口令时可以就地重试，
   * 而不必再去文件选择器里挑一次文件。
   */
  val awaitingBackupPassword: String? = null,
  /** 上一次口令错误，界面据此提示「口令不对」而不是清空输入。 */
  val lastBackupPasswordWrong: Boolean = false,
  /** 创建备份时是否需要先问口令。 */
  val awaitingBackupPasswordForCreate: Boolean = false,
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

  /** 用户在「创建备份」对话框里点了确定（口令可以为空 = 明文保存）。 */
  fun createBackup(documentId: String, password: String?) {
    runOperation(
      success = { count ->
        if (password.isNullOrEmpty()) "已备份 $count 笔账目（未加密）" else "已备份 $count 笔账目（已加密）"
      }
    ) {
      val backup = repository.createBackup(clock())
      documents.writeText(documentId, BackupCodec.encode(backup, password)).getOrThrow()
      backup.transactions.size
    }
  }

  /** 读入并解析备份文件。解析成功只进入待确认状态，不立即写入。 */
  fun loadBackupForRestore(documentId: String) {
    _state.update { it.copy(isBusy = true, message = null) }
    viewModelScope.launch {
      val text =
        documents.readText(documentId).getOrElse { error ->
          _state.update { it.copy(isBusy = false, message = describeReadFailure(error)) }
          return@launch
        }
      // 去掉可能存在的 BOM：某些编辑器保存 UTF-8 时会带上，会让 JSON 解析直接失败
      applyDecodedBackup(text.removePrefix("\uFEFF"), password = null)
    }
  }

  /** 用户为加密备份输入了口令（或输错后重试）。 */
  fun submitBackupPassword(password: String) {
    val envelope = _state.value.awaitingBackupPassword ?: return
    _state.update { it.copy(lastBackupPasswordWrong = false) }
    viewModelScope.launch { applyDecodedBackup(envelope, password) }
  }

  fun dismissBackupPasswordPrompt() {
    _state.update { it.copy(awaitingBackupPassword = null, lastBackupPasswordWrong = false) }
  }

  private fun applyDecodedBackup(text: String, password: String?) {
    when (val decoded = BackupCodec.decode(text, password)) {
      is BackupCodec.DecodeResult.Ok ->
        _state.update {
          it.copy(
            isBusy = false,
            pendingRestore = decoded.backup,
            awaitingBackupPassword = null,
            lastBackupPasswordWrong = false,
          )
        }

      is BackupCodec.DecodeResult.UnsupportedVersion ->
        _state.update {
          it.copy(
            isBusy = false,
            message =
              "备份版本不兼容（文件为 v${decoded.found}，当前支持 v${decoded.expected}），已取消恢复",
          )
        }

      // 这份备份是加密的，向用户要口令；文件正文先留在状态里，便于输错后就地重试
      BackupCodec.DecodeResult.PasswordRequired ->
        _state.update { it.copy(isBusy = false, awaitingBackupPassword = text) }

      BackupCodec.DecodeResult.WrongPassword ->
        _state.update {
          it.copy(isBusy = false, lastBackupPasswordWrong = true, awaitingBackupPassword = text)
        }

      // 刻意不回显 e.message：kotlinx-serialization 的异常消息里会带上
      // 「JSON input: ...」这样的输入片段，那会把备份内容（分类名等）显示到屏幕上，
      // 进而被截图或最近任务捕获。细节只留在内部，界面给固定文案。
      is BackupCodec.DecodeResult.Malformed ->
        _state.update {
          it.copy(isBusy = false, message = "备份文件无法解析，请确认选择的是本应用导出的备份文件")
        }
    }
  }

  /**
   * 把读取失败翻译成用户能理解的话。
   *
   * 同样不回显异常原文：`FileNotFoundException` 之类会带出 content URI（用户文件路径）。
   */
  private fun describeReadFailure(error: Throwable): String =
    when (error) {
      is DocumentTooLargeException ->
        "文件过大（超过 ${AndroidDocumentStore.MAX_DOCUMENT_BYTES / 1024 / 1024} MB），已取消"
      else -> "读取文件失败，请确认该文件仍可访问"
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
