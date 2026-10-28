package com.moneytracker.app.ui.screenshots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.io.AndroidDocumentStore
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.ui.rememberLedgerRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 截图记账页。
 *
 * 只从系统文件选择器取图，因此**不需要任何权限**；识别全程在本机完成。
 * 识别结果一定要过一遍预览，不直接入库——OCR 一定会错，让用户看一眼是唯一的防线。
 */
@Composable
fun ScreenshotImportScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  repository: LedgerRepository = rememberLedgerRepository(),
) {
  val context = LocalContext.current
  val documents = remember(context) { AndroidDocumentStore(context.applicationContext) }
  val viewModel: ScreenshotImportViewModel =
    viewModel { ScreenshotImportViewModel(repository, documents) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      if (uris.isNotEmpty()) viewModel.startRecognition(uris.map { it.toString() })
    }

  ScreenshotImportContent(
    state = state,
    onBack = onBack,
    onPick = { picker.launch(arrayOf("image/*")) },
    onToggle = viewModel::toggle,
    onSelectAll = viewModel::setAllSelected,
    onSetCategory = viewModel::setCategory,
    onSetDate = viewModel::setDate,
    onImport = viewModel::importSelected,
    onClear = viewModel::clearRows,
    onMessageShown = viewModel::onMessageShown,
    modifier = modifier,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenshotImportContent(
  state: ScreenshotImportState,
  onBack: () -> Unit,
  onPick: () -> Unit,
  onToggle: (String) -> Unit,
  onSelectAll: (Boolean) -> Unit,
  onSetCategory: (String, Long) -> Unit,
  onSetDate: (String, LocalDate) -> Unit,
  onImport: () -> Unit,
  onClear: () -> Unit,
  onMessageShown: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  LaunchedEffect(state.message) {
    state.message?.let {
      snackbarHostState.showSnackbar(it)
      onMessageShown()
    }
  }

  var editingDateFor by remember { mutableStateOf<ScreenshotRow?>(null) }

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text("截图记账") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    bottomBar = {
      if (state.hasRows) {
        Surface(tonalElevation = 3.dp) {
          Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            TextButton(
              onClick = { onSelectAll(state.selectedCount != state.rows.size) },
              modifier = Modifier.testTag("screenshot-select-all"),
            ) {
              Text(if (state.selectedCount == state.rows.size) "全不选" else "全选")
            }
            Spacer(Modifier.weight(1f))
            Button(
              onClick = onImport,
              enabled = !state.isBusy && state.selectedCount > 0,
              modifier = Modifier.testTag("screenshot-import"),
            ) {
              Text("导入 ${state.selectedCount} 笔")
            }
          }
        }
      }
    },
  ) { innerPadding ->
    Column(
      Modifier.padding(innerPadding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .testTag("screenshot-content")
    ) {
      if (!state.hasRows && !state.isBusy) {
        IntroCard(onPick = onPick)
      }

      if (state.isBusy) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
              text = state.progress ?: "正在处理…",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      if (state.hasRows) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "共 ${state.rows.size} 笔" +
              if (state.skippedRows > 0) "，跳过 ${state.skippedRows} 行（金额被屏幕裁切）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.weight(1f))
          TextButton(onClick = onClear, modifier = Modifier.testTag("screenshot-clear")) {
            Text("重选")
          }
        }

        state.grouped.forEach { (imageName, rows) ->
          Text(
            text = imageName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
          )
          rows.forEach { row ->
            ScreenshotRowItem(
              row = row,
              options = state.categoriesFor(row.type),
              onToggle = { onToggle(row.id) },
              onSetCategory = { onSetCategory(row.id, it) },
              onEditDate = { editingDateFor = row },
            )
            HorizontalDivider()
          }
        }
        Spacer(Modifier.height(24.dp))
      }
    }
  }

  editingDateFor?.let { row ->
    val initialMillis =
      Instant.ofEpochMilli(row.occurredAtMillis).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
      onDismissRequest = { editingDateFor = null },
      confirmButton = {
        TextButton(
          onClick = {
            pickerState.selectedDateMillis?.let { millis ->
              onSetDate(row.id, Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
            }
            editingDateFor = null
          },
          modifier = Modifier.testTag("screenshot-date-confirm"),
        ) {
          Text("确定")
        }
      },
      dismissButton = {
        TextButton(onClick = { editingDateFor = null }) { Text("取消") }
      },
    ) {
      DatePicker(state = pickerState)
    }
  }
}

@Composable
private fun IntroCard(onPick: () -> Unit) {
  Card(Modifier.fillMaxWidth().padding(16.dp)) {
    Column(Modifier.padding(16.dp)) {
      Text("从截图记账", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(6.dp))
      Text(
        text =
          "选择微信、支付宝或银行 App 的账单截图，本机会把上面的账目读出来，" +
            "确认后写入账本。\n\n" +
            "· 支持一次选多张，一屏有多笔会逐笔列出\n" +
            "· 识别在本机完成，不上传、不联网\n" +
            "· 金额只读；日期与分类可以在下面改\n" +
            "· 同一张截图重复导入不会重复记账",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(12.dp))
      OutlinedButton(onClick = onPick, modifier = Modifier.testTag("screenshot-pick")) {
        Text("选择截图")
      }
    }
  }
}

@Composable
private fun ScreenshotRowItem(
  row: ScreenshotRow,
  options: List<CategoryEntity>,
  onToggle: () -> Unit,
  onSetCategory: (Long) -> Unit,
  onEditDate: () -> Unit,
) {
  val zone = remember { ZoneId.systemDefault() }
  var menuOpen by remember { mutableStateOf(false) }
  val categoryName = options.firstOrNull { it.id == row.categoryId }?.name ?: "未分类"

  Row(
    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(
      checked = row.selected,
      onCheckedChange = { onToggle() },
      modifier = Modifier.testTag("screenshot-row-${row.id}"),
    )
    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
      Text(
        text = row.merchant ?: "（未识别到商户）",
        style = MaterialTheme.typography.bodyMedium,
      )
      val notes = buildList {
        if (row.suspicious) add("疑似资金划转")
        if (row.timeUncertain) add("日期待确认")
        if (row.directionUncertain) add("方向待确认")
        row.categoryLabel?.let { add(it) }
      }
      if (notes.isNotEmpty()) {
        Text(
          text = notes.joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onEditDate, modifier = Modifier.testTag("screenshot-date-${row.id}")) {
          Text(
            text =
              Instant.ofEpochMilli(row.occurredAtMillis)
                .atZone(zone)
                .toLocalDateTime()
                .format(DATE_FORMAT),
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Box {
          TextButton(
            onClick = { menuOpen = true },
            modifier = Modifier.testTag("screenshot-category-${row.id}"),
          ) {
            Text(categoryName, style = MaterialTheme.typography.bodySmall)
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { option ->
              DropdownMenuItem(
                text = { Text(option.name) },
                onClick = {
                  onSetCategory(option.id)
                  menuOpen = false
                },
              )
            }
          }
        }
      }
    }
    Spacer(Modifier.width(8.dp))
    // 金额只读：实测识别准确率接近 100%，而做成可编辑要付出三个编辑控件与校验的代价
    Text(
      text = (if (row.type == TransactionType.EXPENSE) "-" else "+") + "¥" + row.amountText,
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (row.type == TransactionType.EXPENSE) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.primary,
    )
  }
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm")
