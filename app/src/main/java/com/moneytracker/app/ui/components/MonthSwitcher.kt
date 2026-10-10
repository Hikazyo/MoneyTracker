package com.moneytracker.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 月份切换器：‹ 2026年10月 ›
 *
 * 账目列表页与统计页共用。不在这里套 Surface——由调用方决定背景层级。
 *
 * 不在本月时，点标题可一键回到本月：跨了好几个月后，连按十几次「下个月」太折磨人。
 */
@Composable
fun MonthSwitcher(
  monthLabel: String,
  isCurrentMonth: Boolean,
  onPreviousMonth: () -> Unit,
  onNextMonth: () -> Unit,
  onCurrentMonth: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onPreviousMonth) {
      Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "上个月")
    }
    Text(
      text = monthLabel,
      style = MaterialTheme.typography.titleMedium,
      textAlign = TextAlign.Center,
      modifier = Modifier.weight(1f).clickable(enabled = !isCurrentMonth, onClick = onCurrentMonth),
    )
    IconButton(onClick = onNextMonth) {
      Icon(Icons.Default.KeyboardArrowRight, contentDescription = "下个月")
    }
  }
}
