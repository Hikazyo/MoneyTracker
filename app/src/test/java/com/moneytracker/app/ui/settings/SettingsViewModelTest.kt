package com.moneytracker.app.ui.settings

import com.moneytracker.app.data.backup.BackupCodec
import com.moneytracker.app.data.backup.BackupCategory
import com.moneytracker.app.data.backup.BackupFile
import com.moneytracker.app.data.backup.BackupTransaction
import com.moneytracker.app.data.db.dao.CategoryTotal
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.db.entity.TransactionEntity
import com.moneytracker.app.data.export.CsvExporter
import com.moneytracker.app.data.export.ExportEntry
import com.moneytracker.app.data.io.DocumentStore
import com.moneytracker.app.data.model.MonthKey
import com.moneytracker.app.data.model.MonthTotal
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.repository.BudgetSaveResult
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.data.repository.MonthBudget
import com.moneytracker.app.data.repository.MonthLedger
import com.moneytracker.app.data.repository.RestoreResult
import com.moneytracker.app.testing.MainDispatcherRule
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

  private val fixedNow: Long =
    LocalDate.of(2026, 10, 10).atTime(14, 30).atZone(zone).toInstant().toEpochMilli()

  private fun viewModel(
    repository: FakeSettingsRepository = FakeSettingsRepository(),
    documents: FakeDocumentStore = FakeDocumentStore(),
  ) = SettingsViewModel(repository, documents, zone, clock = { fixedNow })

  // ---------- CSV 导出 ----------

  @Test
  fun export_writes_csv_with_bom_to_the_chosen_document() = runTest(mainDispatcherRule.dispatcher) {
    val documents = FakeDocumentStore()
    val viewModel = viewModel(documents = documents)

    viewModel.exportCsv("content://docs/1")
    advance()

    val written = documents.contentOf("content://docs/1")
    assertNotNull(written)
    assertTrue("必须带 UTF-8 BOM，否则 Excel 打开中文是乱码", written!!.startsWith(CsvExporter.UTF8_BOM))
    assertTrue(written.contains("日期,类型,分类,金额,备注"))
    assertTrue(written.contains("餐饮"))
  }

  @Test
  fun export_reports_the_number_of_entries() = runTest(mainDispatcherRule.dispatcher) {
    val viewModel = viewModel()
    viewModel.exportCsv("content://docs/1")
    advance()

    assertEquals("已导出 2 笔账目", viewModel.state.value.message)
  }

  @Test
  fun write_failure_is_reported_and_success_is_not_claimed() =
    runTest(mainDispatcherRule.dispatcher) {
      val viewModel = viewModel(documents = FakeDocumentStore(failWrite = true))
      viewModel.exportCsv("content://docs/1")
      advance()

      val message = viewModel.state.value.message
      assertNotNull(message)
      assertTrue("失败时不能说已导出", !message!!.startsWith("已导出"))
      assertTrue(message.contains("操作失败"))
    }

  // ---------- 备份 ----------

  @Test
  fun backup_writes_json_carrying_the_schema_version() = runTest(mainDispatcherRule.dispatcher) {
    val documents = FakeDocumentStore()
    val viewModel = viewModel(documents = documents)

    viewModel.createBackup("content://docs/2")
    advance()

    val written = documents.contentOf("content://docs/2")!!
    assertTrue(written.contains("\"schemaVersion\": ${BackupCodec.CURRENT_SCHEMA_VERSION}"))
    assertEquals("已备份 2 笔账目", viewModel.state.value.message)
  }

  // ---------- 恢复：必须先确认 ----------

  @Test
  fun selecting_a_backup_only_asks_for_confirmation() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeSettingsRepository()
    val documents = FakeDocumentStore().apply { put("content://docs/3", validBackupJson()) }
    val viewModel = viewModel(repository, documents)

    viewModel.loadBackupForRestore("content://docs/3")
    advance()

    assertNotNull("应进入待确认状态", viewModel.state.value.pendingRestore)
    assertNull("确认之前绝不能写入", repository.restoredFrom)
  }

  @Test
  fun confirming_performs_the_restore() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeSettingsRepository()
    val documents = FakeDocumentStore().apply { put("content://docs/3", validBackupJson()) }
    val viewModel = viewModel(repository, documents)

    viewModel.loadBackupForRestore("content://docs/3")
    advance()
    viewModel.confirmRestore()
    advance()

    assertNotNull(repository.restoredFrom)
    assertNull(viewModel.state.value.pendingRestore)
    assertEquals("已恢复 2 笔账目", viewModel.state.value.message)
  }

  @Test
  fun dismissing_the_confirmation_writes_nothing() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeSettingsRepository()
    val documents = FakeDocumentStore().apply { put("content://docs/3", validBackupJson()) }
    val viewModel = viewModel(repository, documents)

    viewModel.loadBackupForRestore("content://docs/3")
    advance()
    viewModel.dismissRestore()
    advance()

    assertNull(viewModel.state.value.pendingRestore)
    assertNull("取消后不得恢复", repository.restoredFrom)
  }

  // ---------- 恢复：拒绝坏文件 ----------

  @Test
  fun incompatible_version_is_rejected_without_touching_data() =
    runTest(mainDispatcherRule.dispatcher) {
      val repository = FakeSettingsRepository()
      val documents =
        FakeDocumentStore().apply {
          put(
            "content://docs/4",
            validBackupJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"),
          )
        }
      val viewModel = viewModel(repository, documents)

      viewModel.loadBackupForRestore("content://docs/4")
      advance()

      assertNull("版本不符不应进入确认流程", viewModel.state.value.pendingRestore)
      assertNull(repository.restoredFrom)
      assertTrue(viewModel.state.value.message!!.contains("版本不兼容"))
    }

  @Test
  fun malformed_file_is_reported() = runTest(mainDispatcherRule.dispatcher) {
    val repository = FakeSettingsRepository()
    val documents = FakeDocumentStore().apply { put("content://docs/5", "这不是 JSON") }
    val viewModel = viewModel(repository, documents)

    viewModel.loadBackupForRestore("content://docs/5")
    advance()

    assertNull(viewModel.state.value.pendingRestore)
    assertNull(repository.restoredFrom)
    assertTrue(viewModel.state.value.message!!.contains("无法解析"))
  }

  @Test
  fun unreadable_file_is_reported() = runTest(mainDispatcherRule.dispatcher) {
    val viewModel = viewModel(documents = FakeDocumentStore(failRead = true))
    viewModel.loadBackupForRestore("content://docs/missing")
    advance()

    assertTrue(viewModel.state.value.message!!.contains("读取文件失败"))
  }

  @Test
  fun a_bom_in_the_backup_file_is_tolerated() = runTest(mainDispatcherRule.dispatcher) {
    // 某些编辑器保存 UTF-8 会带 BOM，直接喂给 JSON 解析会失败
    val documents =
      FakeDocumentStore().apply { put("content://docs/6", CsvExporter.UTF8_BOM + validBackupJson()) }
    val viewModel = viewModel(documents = documents)

    viewModel.loadBackupForRestore("content://docs/6")
    advance()

    assertNotNull("带 BOM 的备份应能正常解析", viewModel.state.value.pendingRestore)
  }

  @Test
  fun restore_failure_mentions_that_existing_data_is_untouched() =
    runTest(mainDispatcherRule.dispatcher) {
      val repository = FakeSettingsRepository(restoreResult = RestoreResult.Failure("外键约束失败"))
      val documents = FakeDocumentStore().apply { put("content://docs/7", validBackupJson()) }
      val viewModel = viewModel(repository, documents)

      viewModel.loadBackupForRestore("content://docs/7")
      advance()
      viewModel.confirmRestore()
      advance()

      val message = viewModel.state.value.message!!
      assertTrue(message.contains("恢复失败"))
      assertTrue("必须明确告知现有数据未受影响", message.contains("现有数据未改动"))
    }

  // ---------- 建议文件名 ----------

  @Test
  fun suggested_names_are_derived_from_the_clock() {
    val viewModel = viewModel()
    assertEquals("moneytracker-2026-10-10.csv", viewModel.suggestedCsvName)
    assertEquals("moneytracker-backup-2026-10-10.json", viewModel.suggestedBackupName)
  }

  @Test
  fun message_can_be_cleared_after_being_shown() = runTest(mainDispatcherRule.dispatcher) {
    val viewModel = viewModel()
    viewModel.exportCsv("content://docs/1")
    advance()
    assertNotNull(viewModel.state.value.message)

    viewModel.onMessageShown()
    assertNull(viewModel.state.value.message)
  }

  private fun advance() {
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
  }

  private fun validBackupJson(): String =
    BackupCodec.encode(
      BackupFile(
        schemaVersion = BackupCodec.CURRENT_SCHEMA_VERSION,
        exportedAt = fixedNow,
        categories =
          listOf(BackupCategory(1, "餐饮", "food", "EXPENSE", 0, isBuiltIn = true, isArchived = false)),
        transactions =
          listOf(
            BackupTransaction(1, 3200, "EXPENSE", 1, "午饭", fixedNow, 1L, 1L),
            BackupTransaction(2, 4400, "EXPENSE", 1, null, fixedNow, 1L, 1L),
          ),
      )
    )
}

private class FakeDocumentStore(
  private val files: MutableMap<String, String> = mutableMapOf(),
  private val failWrite: Boolean = false,
  private val failRead: Boolean = false,
) : DocumentStore {

  override suspend fun writeText(documentId: String, text: String): Result<Unit> =
    if (failWrite) {
      Result.failure(IOException("磁盘已满"))
    } else {
      files[documentId] = text
      Result.success(Unit)
    }

  override suspend fun readText(documentId: String): Result<String> =
    if (failRead) {
      Result.failure(IOException("文件不存在"))
    } else {
      files[documentId]?.let { Result.success(it) } ?: Result.failure(IOException("文件不存在"))
    }

  override suspend fun readBytes(documentId: String): Result<ByteArray> =
    readText(documentId).map { it.toByteArray(Charsets.UTF_8) }

  fun contentOf(documentId: String): String? = files[documentId]

  fun put(documentId: String, content: String) {
    files[documentId] = content
  }
}

private class FakeSettingsRepository(
  private val restoreResult: RestoreResult = RestoreResult.Success(2),
) : LedgerRepository {

  var restoredFrom: BackupFile? = null
    private set

  private val entries =
    listOf(
      ExportEntry(1_700_000_000_000L, TransactionType.EXPENSE, "餐饮", 3200L, "午饭"),
      ExportEntry(1_700_000_100_000L, TransactionType.EXPENSE, "交通", 880L, null),
    )

  override suspend fun exportEntries(): List<ExportEntry> = entries

  override suspend fun createBackup(exportedAt: Long): BackupFile =
    BackupFile(
      schemaVersion = BackupCodec.CURRENT_SCHEMA_VERSION,
      exportedAt = exportedAt,
      categories = emptyList(),
      transactions = entries.mapIndexed { index, e ->
        BackupTransaction(
          id = (index + 1).toLong(),
          amountMinor = e.amountMinor,
          type = e.type.name,
          categoryId = 1L,
          note = e.note,
          occurredAt = e.occurredAt,
          createdAt = 1L,
          updatedAt = 1L,
        )
      },
    )

  override suspend fun restoreBackup(backup: BackupFile): RestoreResult {
    restoredFrom = backup
    return restoreResult
  }

  override suspend fun importEntries(
    entries: List<com.moneytracker.app.data.importer.ImportedEntry>,
    categoryIdByIconKey: Map<String, Long>,
    fallbackCategoryId: Long,
  ): com.moneytracker.app.data.repository.ImportResult =
    com.moneytracker.app.data.repository.ImportResult(entries.size, 0)

  override fun observeCategories(type: TransactionType?): Flow<List<CategoryEntity>> =
    flowOf(emptyList())

  override fun observeAllCategories(): Flow<List<CategoryEntity>> = flowOf(emptyList())

  override fun observeMonth(month: MonthKey): Flow<MonthLedger> =
    flowOf(MonthLedger(month, emptyList(), 0L, 0L))

  override fun observeBudget(month: MonthKey): Flow<MonthBudget> =
    flowOf(MonthBudget(month, null, emptyMap(), emptyMap()))

  override fun observeCategoryTotals(
    month: MonthKey,
    type: TransactionType,
  ): Flow<List<CategoryTotal>> = flowOf(emptyList())

  override fun observeMonthlyTrend(
    endMonth: MonthKey,
    months: Int,
  ): Flow<List<MonthTotal>> = flowOf(emptyList())

  override suspend fun findTransaction(id: Long): TransactionEntity? = null

  override suspend fun addTransaction(
    amountMinor: Long,
    type: TransactionType,
    categoryId: Long,
    note: String?,
    occurredAt: Long,
  ): Long = 0L

  override suspend fun updateTransaction(
    id: Long,
    amountMinor: Long,
    type: TransactionType,
    categoryId: Long,
    note: String?,
    occurredAt: Long,
  ) {}

  override suspend fun deleteTransaction(id: Long) {}

  override suspend fun saveMonthlyBudget(
    month: MonthKey,
    amountMinor: Long?,
  ): BudgetSaveResult = BudgetSaveResult.Saved

  override suspend fun saveCategoryBudget(
    month: MonthKey,
    categoryId: Long,
    amountMinor: Long?,
    allowOverBudget: Boolean,
  ): BudgetSaveResult = BudgetSaveResult.Saved

  override suspend fun addCategory(name: String, iconKey: String, type: TransactionType): Long = 0L

  override suspend fun archiveCategory(id: Long) {}

  override suspend fun unarchiveCategory(id: Long) {}

  override suspend fun updateCategory(id: Long, name: String, iconKey: String) {}

  override suspend fun changeCategoryType(id: Long, type: TransactionType): Boolean = false

  override suspend fun deleteCategory(id: Long): Boolean = false

  override fun observeCategoryCounts(): Flow<Map<Long, Int>> = flowOf(emptyMap())
}
