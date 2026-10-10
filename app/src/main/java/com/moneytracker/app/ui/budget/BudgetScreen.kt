package com.moneytracker.app.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.theme.MoneyTrackerTheme
import com.moneytracker.app.ui.components.CategoryVisuals
import com.moneytracker.app.ui.components.MonthSwitcher
import com.moneytracker.app.ui.rememberLedgerRepository

@Composable
fun BudgetScreen(
  modifier: Modifier = Modifier,
  repository: LedgerRepository = rememberLedgerRepository(),
) {
  val viewModel: BudgetViewModel = viewModel { BudgetViewModel(repository) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  BudgetContent(
    state = state,
    onPreviousMonth = viewModel::showPreviousMonth,
    onNextMonth = viewModel::showNextMonth,
    onCurrentMonth = viewModel::showCurrentMonth,
    onSaveTotal = viewModel::saveTotalBudget,
    onSaveCategory = viewModel::saveCategoryBudget,
    onConfirmOverBudget = viewModel::confirmOverBudgetSave,
    onDismissOverBudget = viewModel::dismissOverBudgetSave,
    onMessageShown = viewModel::onMessageShown,
    modifier = modifier,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetContent(
  state: BudgetUiState,
  onPreviousMonth: () -> Unit,
  onNextMonth: () -> Unit,
  onCurrentMonth: () -> Unit,
  onSaveTotal: (Long?) -> Unit,
  onSaveCategory: (Long, Long?) -> Unit,
  onConfirmOverBudget: () -> Unit,
  onDismissOverBudget: () -> Unit,
  onMessageShown: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val snackbarHostState = remember { SnackbarHostState() }

  // 弹窗的目标：null 表示未打开
  var editingTotal by remember { mutableStateOf(false) }
  var editingCategory by remember { mutableStateOf<CategoryBudgetRow?>(null) }

  LaunchedEffect(state.message) {
    state.message?.let {
      snackbarHostState.showSnackbar(it)
      onMessageShown()
    }
  }

  Scaffold(
    modifier = modifier,
    topBar = { TopAppBar(title = { Text("预算") }) },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    Column(Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState())) {
      Surface(tonalElevation = 2.dp) {
        MonthSwitcher(
          monthLabel = state.monthLabel,
          isCurrentMonth = state.isCurrentMonth,
          onPreviousMonth = onPreviousMonth,
          onNextMonth = onNextMonth,
          onCurrentMonth = onCurrentMonth,
        )
      }

      when {
        state.errorMessage != null -> Message("加载失败：${state.errorMessage}")
        state.isLoading -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
          CircularProgressIndicator()
        }
        else -> {
          TotalBudgetCard(
            state = state,
            onEdit = { editingTotal = true },
          )

          if (state.categorySumExceedsTotal) {
            CategorySumWarning(state)
          }

          CategoryBudgetCard(rows = state.categoryRows, onEdit = { editingCategory = it })
        }
      }

      Spacer(Modifier.height(24.dp))
    }
  }

  if (editingTotal) {
    BudgetInputDialog(
      title = "月度总预算",
      // 必须用 Money.format（不带千分位），否则回填的文案 parse 不回来
      initialText = state.totalBudgetMinor?.let { Money.format(it) } ?: "",
      allowClear = state.totalBudgetMinor != null,
      onDismiss = { editingTotal = false },
      onConfirm = {
        onSaveTotal(it)
        editingTotal = false
      },
      onClear = {
        onSaveTotal(null)
        editingTotal = false
      },
    )
  }

  editingCategory?.let { row ->
    BudgetInputDialog(
      title = "${row.name}预算",
      initialText = row.budgetMinor?.let { Money.format(it) } ?: "",
      allowClear = row.budgetMinor != null,
      onDismiss = { editingCategory = null },
      onConfirm = {
        onSaveCategory(row.categoryId, it)
        editingCategory = null
      },
      onClear = {
        onSaveCategory(row.categoryId, null)
        editingCategory = null
      },
    )
  }

  state.pending?.let { pending ->
    val name = state.categoryRows.firstOrNull { it.categoryId == pending.categoryId }?.name ?: "该分类"
    AlertDialog(
      onDismissRequest = onDismissOverBudget,
      title = { Text("分类预算之和超过总预算") },
      text = {
        Text(
          "把「$name」设为 ¥${Money.formatGrouped(pending.requestedAmountMinor)} 后，" +
            "分类预算之和将达 ¥${Money.formatGrouped(pending.requestedSumMinor)}，" +
            "超过总预算 ¥${Money.formatGrouped(pending.totalMinor)}。仍要保存吗？"
        )
      },
      confirmButton = {
        TextButton(
          onClick = onConfirmOverBudget,
          modifier = Modifier.testTag("over-budget-confirm"),
        ) {
          Text("仍然保存")
        }
      },
      dismissButton = {
        TextButton(onClick = onDismissOverBudget, modifier = Modifier.testTag("over-budget-cancel")) {
          Text("取消")
        }
      },
      modifier = Modifier.testTag("over-budget-dialog"),
    )
  }
}

@Composable
private fun TotalBudgetCard(state: BudgetUiState, onEdit: () -> Unit) {
  Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("月度总预算", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onEdit, modifier = Modifier.testTag("edit-total-budget")) {
          Text(if (state.totalBudgetMinor == null) "设置" else "修改")
        }
      }

      if (state.totalBudgetMinor == null) {
        Text(
          text = "还没有设置总预算",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
          text = "¥${state.totalBudgetText}",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.testTag("total-budget-value"),
        )
        Spacer(Modifier.height(12.dp))
        BudgetProgressBar(ratio = state.totalRatio ?: 0f, isOver = state.totalIsOver)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "已用 ${state.totalSpentText}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.weight(1f))
          Text(
            text = state.totalRemainingLabel.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color =
              if (state.totalIsOver) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (state.totalIsOver) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.testTag("total-budget-remaining"),
          )
        }
      }
    }
  }
}

@Composable
private fun CategorySumWarning(state: BudgetUiState) {
  Surface(
    color = MaterialTheme.colorScheme.errorContainer,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("budget-warning"),
  ) {
    Text(
      text =
        "分类预算之和 ¥${state.categoryBudgetSumText} 已超过总预算 ¥${state.totalBudgetText}，" +
          "建议调整其中一项",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onErrorContainer,
      modifier = Modifier.padding(12.dp),
    )
  }
}

@Composable
private fun CategoryBudgetCard(rows: List<CategoryBudgetRow>, onEdit: (CategoryBudgetRow) -> Unit) {
  Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Column(Modifier.fillMaxWidth()) {
      Text(
        text = "分类预算",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      )
      rows.forEachIndexed { index, row ->
        CategoryBudgetRowItem(row = row, onClick = { onEdit(row) })
        if (index != rows.lastIndex) {
          HorizontalDivider(Modifier.padding(start = 68.dp, end = 16.dp))
        }
      }
    }
  }
}

@Composable
private fun CategoryBudgetRowItem(row: CategoryBudgetRow, onClick: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp)
        .testTag("category-row-${row.categoryId}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier.size(36.dp).clip(CircleShape).background(CategoryVisuals.bubbleColor(row.categoryId)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = CategoryVisuals.emojiFor(row.iconKey, row.name),
        style = MaterialTheme.typography.bodyLarge,
      )
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = row.name,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        Text(
          text = row.budgetText,
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (row.budgetMinor == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
      }
      if (row.budgetMinor != null) {
        Spacer(Modifier.height(6.dp))
        BudgetProgressBar(ratio = row.ratio ?: 0f, isOver = row.isOver)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "已用 ${row.spentText}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.weight(1f))
          Text(
            text = row.remainingLabel.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color =
              if (row.isOver) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (row.isOver) FontWeight.SemiBold else FontWeight.Normal,
          )
        }
      }
    }
  }
}

/**
 * 预算进度条。
 *
 * 单独抽出来是为了隔离 Material3 的 API 变化，同时统一「超支用错误色」的处理。
 * 比例可以大于 1，这里只负责在绘制时截断，判断超支仍由调用方依据原始比例决定。
 */
@Composable
private fun BudgetProgressBar(ratio: Float, isOver: Boolean) {
  LinearProgressIndicator(
    progress = { ratio.coerceIn(0f, 1f) },
    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
    color = if (isOver) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    trackColor = MaterialTheme.colorScheme.surfaceVariant,
  )
}

@Composable
private fun Message(text: String) {
  Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

// ---------- 预览 ----------

private val PREVIEW_STATE =
  BudgetUiState(
    isLoading = false,
    monthLabel = "2026年10月",
    isCurrentMonth = true,
    totalBudgetMinor = 100_000L,
    totalBudgetText = "1,000.00",
    totalSpentText = "608.00",
    totalRatio = 0.608f,
    totalRemainingLabel = "剩余 392.00",
    totalIsOver = false,
    categoryBudgetSumMinor = 120_000L,
    categoryBudgetSumText = "1,200.00",
    categorySumExceedsTotal = true,
    categoryRows =
      listOf(
        CategoryBudgetRow(1, "餐饮", "food", 50_000L, "500.00", 32_000L, "320.00", 0.64f, false, "剩余 180.00"),
        CategoryBudgetRow(2, "交通", "transport", 20_000L, "200.00", 8_800L, "88.00", 0.44f, false, "剩余 112.00"),
        CategoryBudgetRow(3, "购物", "shopping", 15_000L, "150.00", 20_000L, "200.00", 1.33f, true, "超支 50.00"),
        CategoryBudgetRow(4, "娱乐", "entertainment", null, "未设置", 0L, "0.00", null, false, null),
      ),
  )

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun BudgetContentPreview() {
  MoneyTrackerTheme {
    BudgetContent(
      state = PREVIEW_STATE,
      onPreviousMonth = {},
      onNextMonth = {},
      onCurrentMonth = {},
      onSaveTotal = {},
      onSaveCategory = { _, _ -> },
      onConfirmOverBudget = {},
      onDismissOverBudget = {},
      onMessageShown = {},
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun BudgetContentEmptyPreview() {
  MoneyTrackerTheme {
    BudgetContent(
      state = BudgetUiState(isLoading = false, monthLabel = "2026年10月"),
      onPreviousMonth = {},
      onNextMonth = {},
      onCurrentMonth = {},
      onSaveTotal = {},
      onSaveCategory = { _, _ -> },
      onConfirmOverBudget = {},
      onDismissOverBudget = {},
      onMessageShown = {},
    )
  }
}
