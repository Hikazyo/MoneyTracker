package com.moneytracker.app.ui.statistics

import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.moneytracker.app.ui.components.CategoryVisuals
import com.moneytracker.app.ui.components.MonthSwitcher
import com.moneytracker.app.ui.rememberLedgerRepository

@Composable
fun StatisticsScreen(
  modifier: Modifier = Modifier,
  repository: LedgerRepository = rememberLedgerRepository(),
) {
  val viewModel: StatisticsViewModel = viewModel { StatisticsViewModel(repository) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  StatisticsContent(
    state = state,
    onPreviousMonth = viewModel::showPreviousMonth,
    onNextMonth = viewModel::showNextMonth,
    onCurrentMonth = viewModel::showCurrentMonth,
    onTypeChange = viewModel::setType,
    modifier = modifier,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsContent(
  state: StatisticsUiState,
  onPreviousMonth: () -> Unit,
  onNextMonth: () -> Unit,
  onCurrentMonth: () -> Unit,
  onTypeChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier,
    // 作为底部导航的页签，不需要返回按钮
    topBar = { TopAppBar(title = { Text("统计") }) },
  ) { innerPadding ->
    Column(
      Modifier.padding(innerPadding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .testTag("statistics-content")
    ) {
      Surface(tonalElevation = 2.dp) {
        MonthSwitcher(
          monthLabel = state.monthLabel,
          isCurrentMonth = state.isCurrentMonth,
          onPreviousMonth = onPreviousMonth,
          onNextMonth = onNextMonth,
          onCurrentMonth = onCurrentMonth,
        )
      }

      TypeToggle(isExpense = state.isExpense, onTypeChange = onTypeChange)

      when {
        state.errorMessage != null -> Message("加载失败：${state.errorMessage}")

        state.isLoading -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
          CircularProgressIndicator()
        }

        state.isEmpty -> Message(
          if (state.isExpense) "该月没有支出记录" else "该月没有收入记录"
        )

        else -> {
          ShareCard(state)
          RankingCard(state.slices)
        }
      }

      // 趋势图与「本月有没有数据」无关，始终展示，否则切到空月份就看不到历史走势了
      if (state.trend.isNotEmpty() && state.trend.any { it.expenseMinor > 0 || it.incomeMinor > 0 }) {
        TrendCard(state.trend)
      }

      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun TypeToggle(isExpense: Boolean, onTypeChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FilterChip(
      selected = isExpense,
      onClick = { onTypeChange(true) },
      label = { Text("支出") },
      modifier = Modifier.weight(1f).testTag("type-expense"),
    )
    FilterChip(
      selected = !isExpense,
      onClick = { onTypeChange(false) },
      label = { Text("收入") },
      modifier = Modifier.weight(1f).testTag("type-income"),
    )
  }
}

@Composable
private fun ShareCard(state: StatisticsUiState) {
  val colors = ChartPalette.colors(state.slices.size)

  Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      DonutChart(
        ratios = state.slices.map { it.ratio },
        colors = colors,
        modifier = Modifier.size(200.dp),
        centerLabel = if (state.isExpense) "总支出" else "总收入",
        centerValue = "¥${state.totalText}",
      )
    }
  }
}

@Composable
private fun RankingCard(slices: List<CategorySlice>) {
  val colors = ChartPalette.colors(slices.size)

  Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
      Text(
        text = "分类排行",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
      slices.forEachIndexed { index, slice ->
        RankingRow(slice = slice, color = colors[index % colors.size])
        if (index != slices.lastIndex) {
          HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp))
        }
      }
    }
  }
}

@Composable
private fun RankingRow(slice: CategorySlice, color: androidx.compose.ui.graphics.Color) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp)
        .testTag("slice-${slice.categoryId}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier.size(36.dp).clip(CircleShape).background(CategoryVisuals.bubbleColor(slice.categoryId)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = CategoryVisuals.emojiFor(slice.iconKey, slice.name),
        style = MaterialTheme.typography.bodyLarge,
      )
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = slice.name,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = slice.percentText,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(Modifier.height(6.dp))
      // 占比条：给百分比一个可横向比较的视觉长度
      Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant)
      ) {
        Box(
          Modifier.fillMaxWidth(slice.ratio.coerceIn(0f, 1f))
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
        )
      }
    }
    Spacer(Modifier.width(12.dp))
    Text(
      text = slice.amountText,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
    )
  }
}

@Composable
private fun TrendCard(trend: List<MonthBar>) {
  Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("近 ${trend.size} 个月趋势", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        TrendLegend()
      }
      Spacer(Modifier.height(16.dp))
      TrendBarChart(bars = trend, modifier = Modifier.fillMaxWidth())
    }
  }
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
  StatisticsUiState(
    isLoading = false,
    monthLabel = "2026年10月",
    isCurrentMonth = true,
    isExpense = true,
    totalText = "3,240.00",
    isEmpty = false,
    slices =
      listOf(
        CategorySlice(1, "餐饮", "food", "1,320.00", "40.7%", 0.407f),
        CategorySlice(2, "交通", "transport", "860.00", "26.5%", 0.265f),
        CategorySlice(3, "购物", "shopping", "640.00", "19.8%", 0.198f),
        CategorySlice(4, "娱乐", "entertainment", "420.00", "13.0%", 0.130f),
      ),
    trend =
      listOf(
        MonthBar("5月", 210_000, 800_000, false),
        MonthBar("6月", 260_000, 800_000, false),
        MonthBar("7月", 190_000, 800_000, false),
        MonthBar("8月", 310_000, 900_000, false),
        MonthBar("9月", 280_000, 800_000, false),
        MonthBar("10月", 324_000, 800_000, true),
      ),
  )

@Preview(showBackground = true, heightDp = 1100)
@Composable
private fun StatisticsContentPreview() {
  MoneyTrackerTheme {
    StatisticsContent(
      state = PREVIEW_STATE,
      onPreviousMonth = {},
      onNextMonth = {},
      onCurrentMonth = {},
      onTypeChange = {},
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun StatisticsContentEmptyPreview() {
  MoneyTrackerTheme {
    StatisticsContent(
      state = StatisticsUiState(isLoading = false, monthLabel = "2026年9月", isEmpty = true),
      onPreviousMonth = {},
      onNextMonth = {},
      onCurrentMonth = {},
      onTypeChange = {},
    )
  }
}
