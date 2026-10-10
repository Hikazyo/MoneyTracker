package com.moneytracker.app.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 环形占比图。
 *
 * 手绘而不是引入图表库：图形本身只是若干段圆弧，而需要定制的三点
 * （扇区配色与分类一一对应、中心显示合计、留缝隙区分相邻扇区）在库上反而更绕。
 *
 * @param ratios 各扇区占比，需与 [colors] 一一对应
 */
@Composable
fun DonutChart(
  ratios: List<Float>,
  colors: List<Color>,
  modifier: Modifier = Modifier,
  strokeWidth: Dp = 26.dp,
  centerLabel: String? = null,
  centerValue: String? = null,
) {
  val trackColor = MaterialTheme.colorScheme.surfaceVariant

  Box(modifier, contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val stroke = strokeWidth.toPx()
      val diameter = minOf(size.width, size.height) - stroke
      if (diameter <= 0f || colors.isEmpty()) return@Canvas

      val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
      val arcSize = Size(diameter, diameter)

      // 底环：没有数据时也有视觉主体，不会是一片空白
      drawArc(
        color = trackColor,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke),
      )

      var startAngle = -90f
      ratios.forEachIndexed { index, ratio ->
        if (ratio <= 0f) return@forEachIndexed
        val sweep = 360f * ratio
        // 留缝隙区分相邻扇区；但缝隙不能大到吃掉扇区本身（小占比扇区会消失）
        val gap = minOf(2f, sweep / 4f)
        drawArc(
          color = colors[index % colors.size],
          startAngle = startAngle + gap / 2f,
          sweepAngle = sweep - gap,
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = stroke),
        )
        startAngle += sweep
      }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      if (centerLabel != null) {
        Text(
          text = centerLabel,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (centerValue != null) {
        Text(
          text = centerValue,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}
