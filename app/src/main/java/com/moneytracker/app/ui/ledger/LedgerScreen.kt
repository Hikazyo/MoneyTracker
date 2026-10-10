package com.moneytracker.app.ui.ledger

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.theme.MoneyTrackerTheme
import com.moneytracker.app.theme.expenseColor
import com.moneytracker.app.theme.incomeColor
import com.moneytracker.app.ui.components.CategoryVisuals
import com.moneytracker.app.ui.components.MonthSwitcher
import com.moneytracker.app.ui.rememberLedgerRepository

@Composable
fun LedgerScreen(
  onAddEntry: () -> Unit,
  onEditEntry: (Long) -> Unit,
  modifier: Modifier = Modifier,
  // 在默认参数里调用 @Composable 是合法的（Material3 组件普遍如此）；
  // 但不能把 rememberLedgerRepository() 放进 viewModel {} 的 initializer——
  // 那个 lambda 是 CreationExtras.() -> VM，不是 @Composable 上下文。
  repository: LedgerRepository = rememberLedgerRepository(),
) {
  val viewModel: LedgerViewModel = viewModel { LedgerViewModel(repository) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LedgerContent(
    state = state,
    onPreviousMonth = viewModel::showPreviousMonth,
    onNextMonth = viewModel::showNextMonth,
    onCurrentMonth = viewModel::showCurrentMonth,
    onAddEntry = onAddEntry,
    onEditEntry = onEditEntry,
    modifier = modifier,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LedgerContent(
  state: LedgerUiState,
  onPreviousMonth: () -> Unit,
  onNextMonth: () -> Unit,
  onCurrentMonth: () -> Unit,
  onAddEntry: () -> Unit,
  onEditEntry: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier,
    topBar = { TopAppBar(title = { Text("记账本") }) },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onAddEntry,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text("记一笔") },
        modifier = Modifier.testTag("add-entry"),
      )
    },
  ) { innerPadding ->
    Column(Modifier.padding(innerPadding).fillMaxSize()) {
      Surface(tonalElevation = 2.dp) {
        MonthSwitcher(
          monthLabel = state.monthLabel,
          isCurrentMonth = state.isCurrentMonth,
          onPreviousMonth = onPreviousMonth,
          onNextMonth = onNextMonth,
          onCurrentMonth = onCurrentMonth,
        )
      }

      SummaryCard(state)

      when {
        state.errorMessage != null -> CenteredMessage("加载失败：${state.errorMessage}")

        state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }

        state.isEmpty -> CenteredMessage("这个月还没有记账\n点右下角「记一笔」开始")

        else -> EntryList(dayGroups = state.dayGroups, onEditEntry = onEditEntry)
      }
    }
  }
}

@Composable
private fun SummaryCard(state: LedgerUiState) {
  Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
    Column(Modifier.padding(16.dp)) {
      Row(Modifier.fillMaxWidth()) {
        SummaryItem(
          label = "支出",
          value = "¥${state.expenseText}",
          color = expenseColor(),
          modifier = Modifier.weight(1f),
        )
        SummaryItem(
          label = "收入",
          value = "¥${state.incomeText}",
          color = incomeColor(),
          modifier = Modifier.weight(1f),
        )
      }
      HorizontalDivider(Modifier.padding(vertical = 12.dp))
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("结余", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        // 结余已自带正负号，再加 ¥ 会读成「¥+123」，所以这里不加货币符号
        Text(
          text = state.balanceText,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
private fun SummaryItem(
  label: String,
  value: String,
  color: androidx.compose.ui.graphics.Color,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
      text = value,
      style = MaterialTheme.typography.titleLarge,
      color = color,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryList(dayGroups: List<DayGroup>, onEditEntry: (Long) -> Unit) {
  LazyColumn(Modifier.fillMaxSize()) {
    dayGroups.forEach { group ->
      stickyHeader(key = "header-${group.dayLabel}") { DayHeader(group.dayLabel, group.summary) }
      items(items = group.entries, key = { it.id }) { row ->
        EntryRowItem(row = row, onClick = { onEditEntry(row.id) })
      }
    }
  }
}

@Composable
private fun DayHeader(label: String, summary: String) {
  Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, style = MaterialTheme.typography.labelLarge)
      Spacer(Modifier.weight(1f))
      if (summary.isNotEmpty()) {
        Text(
          text = summary,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun EntryRowItem(row: EntryRow, onClick: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier.size(40.dp).clip(CircleShape).background(CategoryVisuals.bubbleColor(row.id)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = CategoryVisuals.emojiFor(row.iconKey, row.categoryName),
        style = MaterialTheme.typography.titleMedium,
      )
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(row.categoryName, style = MaterialTheme.typography.bodyLarge)
      if (!row.note.isNullOrBlank()) {
        Text(
          text = row.note,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Spacer(Modifier.width(8.dp))
    Column(horizontalAlignment = Alignment.End) {
      Text(
        text = row.signedAmount,
        style = MaterialTheme.typography.titleMedium,
        color = if (row.isExpense) expenseColor() else incomeColor(),
        fontWeight = FontWeight.Medium,
      )
      Text(
        text = row.timeLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun CenteredMessage(text: String) {
  Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
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
  LedgerUiState(
    isLoading = false,
    monthLabel = "2026年10月",
    expenseText = "1,234.50",
    incomeText = "8,000.00",
    balanceText = "+6,765.50",
    isCurrentMonth = true,
    isEmpty = false,
    dayGroups =
      listOf(
        DayGroup(
          dayLabel = "今天",
          summary = "支出 1,234.50",
          entries =
            listOf(
              EntryRow(1, "餐饮", "food", "午饭", "12:30", "-32.50", true),
              EntryRow(2, "交通", "transport", null, "09:15", "-4.00", true),
            ),
        ),
        DayGroup(
          dayLabel = "昨天",
          summary = "收入 8,000.00",
          entries = listOf(EntryRow(3, "工资", "salary", "10月工资", "10:00", "+8,000.00", false)),
        ),
      ),
  )

@Preview(showBackground = true)
@Composable
private fun LedgerContentPreview() {
  MoneyTrackerTheme {
    LedgerContent(
      state = PREVIEW_STATE,
      onPreviousMonth = {},
      onNextMonth = {},
      onCurrentMonth = {},
      onAddEntry = {},
      onEditEntry = {},
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun LedgerContentEmptyPreview() {
  MoneyTrackerTheme {
    LedgerContent(
      state = LedgerUiState(isLoading = false, monthLabel = "2026年10月", isEmpty = true),
      onPreviousMonth = {},
      onNextMonth = {},
      onCurrentMonth = {},
      onAddEntry = {},
      onEditEntry = {},
    )
  }
}
