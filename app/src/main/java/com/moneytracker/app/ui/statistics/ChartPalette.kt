package com.moneytracker.app.ui.statistics

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 图表配色。
 *
 * 按**下标**取色而不是按分类 id：占比图里相邻扇区必须互相可区分，按排名顺序取色最稳。
 * 超过调色板长度就循环使用。
 */
object ChartPalette {

  private val LIGHT =
    listOf(
      Color(0xFF5B8FF9),
      Color(0xFF61DDAA),
      Color(0xFFF6BD16),
      Color(0xFF7262FD),
      Color(0xFF78D3F8),
      Color(0xFF9661BC),
      Color(0xFFF6903D),
      Color(0xFF008685),
      Color(0xFFF08BB4),
      Color(0xFFE8684A),
    )

  /** 深色模式下用更亮的色阶，否则这些颜色在深色背景上偏闷。 */
  private val DARK =
    listOf(
      Color(0xFF7FA8FF),
      Color(0xFF7FE3BC),
      Color(0xFFF7CE5B),
      Color(0xFF9A8DFF),
      Color(0xFF9BDFFA),
      Color(0xFFB08CD0),
      Color(0xFFF8A96A),
      Color(0xFF3FA9A8),
      Color(0xFFF4A6C6),
      Color(0xFFF08A72),
    )

  /** 取 [count] 个颜色（循环使用调色板）。 */
  @Composable
  fun colors(count: Int): List<Color> {
    if (count <= 0) return emptyList()
    val palette = if (isSystemInDarkTheme()) DARK else LIGHT
    return List(count) { index -> palette[index % palette.size] }
  }
}
