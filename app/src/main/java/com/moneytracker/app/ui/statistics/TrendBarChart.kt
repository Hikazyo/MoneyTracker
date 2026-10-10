package com.moneytracker.app.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneytracker.app.theme.expenseColor
import com.moneytracker.app.theme.incomeColor

/**
 * 近几个月收支趋势（分组柱状图）。
 *
 * 用布局而不是 Canvas 画柱子：柱高就是「容器高度 × 占比」，用 Modifier.height 表达
 * 比手算像素更直观，也天然适配不同屏幕。
 */
@Composable
fun TrendBarChart(
  bars: List<MonthBar>,
  modifier: Modifier = Modifier,
  barHeight: Dp = 110.dp,
) {
  val maxMinor = bars.maxOfOrNull { maxOf(it.expenseMinor, it.incomeMinor) } ?: 0L

  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.Bottom,
  ) {
    bars.forEach { bar ->
      Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
          modifier = Modifier.height(barHeight),
          verticalAlignment = Alignment.Bottom,
          horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          TrendBar(bar.expenseMinor, maxMinor, expenseColor(), bar.isSelected, barHeight)
          TrendBar(bar.incomeMinor, maxMinor, incomeColor(), bar.isSelected, barHeight)
        }
        Spacer(Modifier.height(6.dp))
        Text(
          text = bar.label,
          style = MaterialTheme.typography.labelSmall,
          color =
            if (bar.isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
          fontWeight = if (bar.isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
      }
    }
  }
}

@Composable
private fun TrendBar(
  value: Long,
  max: Long,
  color: Color,
  highlighted: Boolean,
  containerHeight: Dp,
) {
  val fraction =
    if (max <= 0L || value <= 0L) 0f
    else (value.toDouble() / max.toDouble()).toFloat().coerceIn(0f, 1f)

  // 有金额但极小值时给个最小可见高度，否则柱子会「消失」，看起来像没有数据
  val height = if (value > 0L) (containerHeight * fraction).coerceAtLeast(3.dp) else 0.dp

  Box(
    Modifier.width(9.dp)
      .height(height)
      .clip(RoundedCornerShape(3.dp))
      .background(if (highlighted) color else color.copy(alpha = 0.4f))
  )
}

/** 图例：说明两种颜色分别代表什么。 */
@Composable
fun TrendLegend(modifier: Modifier = Modifier) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    // 加 testTag 是因为「支出/收入」同时也是筛选芯片的文案，
    // 只按文字查找会匹配到两个节点，测试里断言会失败
    LegendItem("支出", expenseColor(), Modifier.testTag("legend-expense"))
    LegendItem("收入", incomeColor(), Modifier.testTag("legend-income"))
  }
}

@Composable
private fun LegendItem(label: String, color: Color, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
    Spacer(Modifier.width(6.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
