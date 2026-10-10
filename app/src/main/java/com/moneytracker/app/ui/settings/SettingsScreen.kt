package com.moneytracker.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneytracker.app.data.io.AndroidDocumentStore
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.theme.MoneyTrackerTheme
import com.moneytracker.app.ui.importer.ImportPreviewDialog
import com.moneytracker.app.ui.importer.ImportViewModel
import com.moneytracker.app.ui.rememberLedgerRepository

// 导入账单时允许选择的文件类型。
//
// 末尾的 "*/*" 是**故意**保留的兜底：账单文件在不同设备/应用上被上报的类型差别很大，
// 过滤太严会造成「我的账单根本选不了」，比选错文件严重得多——选错只会得到一条明确的
// 解析失败提示。注意这与「恢复备份」的取 strict 策略相反，因为那边的风险是覆盖数据。
//
// xlsx 的完整 MIME 单独列出：微信现在导出的就是 .xlsx，写清楚既是给用户看的，
// 也让「按类型过滤」的文件管理器能正确高亮它。
private val IMPORT_MIME_TYPES =
  arrayOf(
    "text/csv",
    "text/plain",
    "text/comma-separated-values",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx（微信在用）
    "application/octet-stream",
    "*/*",
  )

@Composable
fun SettingsScreen(
  onOpenCategories: () -> Unit,
  onOpenAbout: () -> Unit,
  modifier: Modifier = Modifier,
  repository: LedgerRepository = rememberLedgerRepository(),
) {
  val context = LocalContext.current
  val documents = remember(context) { AndroidDocumentStore(context.applicationContext) }

  val viewModel: SettingsViewModel = viewModel { SettingsViewModel(repository, documents) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  val importViewModel: ImportViewModel = viewModel { ImportViewModel(repository, documents) }
  val importState by importViewModel.state.collectAsStateWithLifecycle()

  // SAF：让用户自己选文件，因此完全不需要申请存储权限
  // 备份口令要跨越「弹对话框 → 起文件选择器 → 拿到 URI」这一串异步步骤，
  // 所以先用一个局部状态接住它。口令只活在内存里，不写任何存储。
  var pendingBackupPassword by remember { mutableStateOf<String?>(null) }
  var showBackupPasswordDialog by remember { mutableStateOf(false) }

  val createCsvLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
      uri?.let { viewModel.exportCsv(it.toString()) }
    }
  val createBackupLauncher =
    rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
      uri?.let { viewModel.createBackup(it.toString(), pendingBackupPassword) }
      pendingBackupPassword = null
    }
  val openBackupLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { viewModel.loadBackupForRestore(it.toString()) }
    }

  // 导入账单的文件选择。
  //
  // 这里刻意**保留** "*/*"：账单文件在不同设备上被上报的类型五花八门
  // （text/csv、text/plain、application/octet-stream 都见过），过滤太严会导致
  // 「我的账单根本选不了」，那比选错文件严重得多——选错只会得到一条明确的解析失败提示。
  // 注意这与「恢复备份」的取 strict 策略相反，因为那边的风险是覆盖数据。
  //
  // xlsx 的完整 MIME 单独列出：微信现在导出的就是 .xlsx，写清楚既是给用户看的，
  // 也让「按类型过滤」的文件管理器能正确高亮它。
  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { importViewModel.startImport(it.toString()) }
    }

  SettingsContent(
    state = state,
    onOpenCategories = onOpenCategories,
    onOpenAbout = onOpenAbout,
    importBusy = importState.isBusy,
    importMessage = importState.message,
    onPickImportFile = { importLauncher.launch(IMPORT_MIME_TYPES) },
    onImportMessageShown = importViewModel::onMessageShown,
    onExportCsv = { createCsvLauncher.launch(viewModel.suggestedCsvName) },
    // 先问口令再选保存位置：编码时需要口令，而且先设口令的交互顺序更自然
    onCreateBackup = { showBackupPasswordDialog = true },
    onPickBackup = {
      // 只接受 JSON 与 octet-stream：加了 "*/*" 会把导出的 CSV 也列进来，用户很容易误选。
      // 保留 octet-stream 是因为部分文件提供方对 .json 上报的就是这个类型。
      openBackupLauncher.launch(arrayOf("application/json", "application/octet-stream"))
    },
    onConfirmRestore = viewModel::confirmRestore,
    onDismissRestore = viewModel::dismissRestore,
    onMessageShown = viewModel::onMessageShown,
    modifier = modifier,
  )

  if (showBackupPasswordDialog) {
    CreateBackupPasswordDialog(
      onDismiss = { showBackupPasswordDialog = false },
      onConfirm = { password ->
        showBackupPasswordDialog = false
        pendingBackupPassword = password
        createBackupLauncher.launch(viewModel.suggestedBackupName)
      },
    )
  }

  // 选中的是加密备份 → 先要口令；口令错误时界面保留输入并提示，可以就地重试
  if (state.awaitingBackupPassword != null) {
    RestoreBackupPasswordDialog(
      wrongPassword = state.lastBackupPasswordWrong,
      onDismiss = viewModel::dismissBackupPasswordPrompt,
      onConfirm = viewModel::submitBackupPassword,
    )
  }

  importState.preview?.let { preview ->
    ImportPreviewDialog(
      preview = preview,
      fallbackExpenseCategoryId = importState.fallbackExpenseCategoryId,
      fallbackIncomeCategoryId = importState.fallbackIncomeCategoryId,
      canConfirm = importState.canConfirm,
      onSelectFallback = importViewModel::selectFallback,
      onConfirm = importViewModel::confirmImport,
      onDismiss = importViewModel::dismissPreview,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
  state: SettingsUiState,
  onExportCsv: () -> Unit,
  onCreateBackup: () -> Unit,
  onPickBackup: () -> Unit,
  onConfirmRestore: () -> Unit,
  onDismissRestore: () -> Unit,
  onMessageShown: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenCategories: () -> Unit = {},
  onOpenAbout: () -> Unit = {},
  importBusy: Boolean = false,
  importMessage: String? = null,
  onPickImportFile: () -> Unit = {},
  onImportMessageShown: () -> Unit = {},
) {
  val snackbarHostState = remember { SnackbarHostState() }

  // 两个来源（本页操作、导入）共用同一个 Snackbar
  LaunchedEffect(state.message, importMessage) {
    val text = state.message ?: importMessage
    if (text != null) {
      snackbarHostState.showSnackbar(text)
      if (state.message != null) onMessageShown() else onImportMessageShown()
    }
  }

  Scaffold(
    modifier = modifier,
    topBar = { TopAppBar(title = { Text("设置") }) },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    Column(
      Modifier.padding(innerPadding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .testTag("settings-content")
    ) {
      ActionCard(
        title = "导入账单",
        description = "支持微信支付账单与支付宝账单导出的 Excel（.xlsx）与 CSV。支付宝的 GBK 编码会自动识别；重复导入同一份账单不会产生重复账目。",
        buttonText = "选择账单文件",
        tag = "import-bill",
        enabled = !state.isBusy && !importBusy,
        onClick = onPickImportFile,
      )

      ActionCard(
        title = "导出 CSV",
        description = "导出全部账目为 CSV，可用 Excel 或其它记账软件打开。文件带 UTF-8 BOM，中文不会乱码。",
        buttonText = "导出",
        tag = "export-csv",
        enabled = !state.isBusy && !importBusy,
        onClick = onExportCsv,
      )

      ActionCard(
        title = "创建备份",
        description = "把全部账目、分类与预算导出为一个文件，用于换机或留底。",
        buttonText = "备份",
        tag = "create-backup",
        enabled = !state.isBusy && !importBusy,
        onClick = onCreateBackup,
      )

      ActionCard(
        title = "从备份恢复",
        description = "选择之前导出的备份文件。注意：恢复会覆盖当前全部数据。",
        buttonText = "选择备份文件",
        tag = "restore-backup",
        enabled = !state.isBusy && !importBusy,
        onClick = onPickBackup,
      )

      ActionCard(
        title = "分类管理",
        description = "新增、重命名或归档分类。归档不会删除历史账目，之后也可以随时恢复。",
        buttonText = "管理分类",
        tag = "manage-categories",
        enabled = true,
        onClick = onOpenCategories,
      )

      ActionCard(
        title = "关于与隐私",
        description = "版本信息、数据存放位置、收集了哪些信息、权限说明与数据删除方式。",
        buttonText = "查看",
        tag = "open-about",
        enabled = true,
        onClick = onOpenAbout,
      )

      if (state.isBusy || importBusy) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }

      Spacer(Modifier.height(24.dp))
    }
  }

  state.pendingRestore?.let { backup ->
    AlertDialog(
      onDismissRequest = onDismissRestore,
      title = { Text("确认恢复") },
      text = {
        Text(
          "备份包含 ${backup.categories.size} 个分类、${backup.transactions.size} 笔账目。" +
            "恢复会覆盖当前全部数据，且此操作不可撤销。确定继续吗？"
        )
      },
      confirmButton = {
        TextButton(onClick = onConfirmRestore, modifier = Modifier.testTag("restore-confirm")) {
          Text("覆盖并恢复")
        }
      },
      dismissButton = {
        TextButton(onClick = onDismissRestore, modifier = Modifier.testTag("restore-cancel")) {
          Text("取消")
        }
      },
      modifier = Modifier.testTag("restore-dialog"),
    )
  }
}

@Composable
private fun ActionCard(
  title: String,
  description: String,
  buttonText: String,
  tag: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(6.dp))
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(12.dp))
      OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Text(buttonText)
      }
    }
  }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun SettingsContentPreview() {
  MoneyTrackerTheme {
    SettingsContent(
      state = SettingsUiState(),
      onExportCsv = {},
      onCreateBackup = {},
      onPickBackup = {},
      onConfirmRestore = {},
      onDismissRestore = {},
      onMessageShown = {},
    )
  }
}
