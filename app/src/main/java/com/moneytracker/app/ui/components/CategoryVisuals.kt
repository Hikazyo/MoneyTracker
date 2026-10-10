package com.moneytracker.app.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 一个可选图标。 */
data class CategoryIcon(val key: String, val emoji: String)

/**
 * 分类的视觉呈现。
 *
 * 用 emoji 而不是内置图标库：material-icons-core 的图标集太小（没有「餐饮」「交通」这类），
 * 而 material-icons-extended 体积很大。emoji 零依赖、覆盖任意分类，
 * 且未知 iconKey 能优雅降级为名称首字。
 *
 * 将来要换成真正的矢量图标，只需替换本文件的映射实现，数据库里的 iconKey 不用动。
 */
object CategoryVisuals {

  /**
   * 图标目录，顺序即选择器里的展示顺序。
   *
   * **key 与数据库里存的值一一对应，因此只能新增、不能改名或删除**——
   * 改名会让已有分类丢图标（[emojiFor] 会降级成名称首字）。
   * 至于顺序，只影响选择器里的排列，调整是安全的。
   */
  private val ICONS: List<Pair<String, String>> =
    listOf(
      // —— 内置分类对应的图标（key 不可更改）——
      "food" to "🍚",
      "transport" to "🚌",
      "shopping" to "🛍",
      "housing" to "🏠",
      "entertainment" to "🎮",
      "medical" to "💊",
      "education" to "📚",
      "communication" to "📱",
      "gift" to "🎁",
      "other_expense" to "📦",
      "salary" to "💰",
      "bonus" to "🎉",
      "investment" to "📈",
      "part_time" to "💼",
      "red_packet" to "🧧",
      "other_income" to "💵",
      // —— v3 补充的内置分类 ——
      "clothes" to "👕",
      "daily" to "🧻",
      "digital" to "💻",
      "beauty" to "💄",
      "sport" to "⚽",
      "pet" to "🐱",
      "baby" to "🍼",
      "travel" to "✈️",
      "car" to "🚗",
      "subscription" to "🔁",
      "refund" to "↩️",
      "reimbursement" to "📋",
      // —— 自定义分类可选的额外图标 ——
      "coffee" to "☕",
      "snack" to "🍿",
      "fruit" to "🍎",
      "taxi" to "🚕",
      "train" to "🚄",
      "bike" to "🚲",
      "fuel" to "⛽",
      "book" to "📖",
      "music" to "🎵",
      "game" to "🕹",
      "art" to "🎨",
      "hospital" to "🏥",
      "water" to "🚰",
      "electric" to "💡",
      "internet" to "🌐",
      "insurance" to "🛡",
      "tax" to "🧾",
      "repair" to "🔧",
      "donate" to "🤝",
      "bank" to "🏦",
      "credit" to "💳",
      "cash" to "🪙",
      "star" to "⭐",
      "heart" to "❤️",
      "leaf" to "🌿",
    )

  private val EMOJI_BY_ICON_KEY: Map<String, String> = ICONS.toMap()

  /** 供图标选择器使用。 */
  val iconChoices: List<CategoryIcon> = ICONS.map { CategoryIcon(it.first, it.second) }

  /** 取分类图标；未知 iconKey 回退为名称首字，保证不会出现空白。 */
  fun emojiFor(iconKey: String, fallbackName: String): String =
    EMOJI_BY_ICON_KEY[iconKey] ?: fallbackName.take(1).ifEmpty { "·" }

  private val BUBBLE_PALETTE =
    listOf(
      Color(0xFFFFE0B2),
      Color(0xFFB3E5FC),
      Color(0xFFC8E6C9),
      Color(0xFFF8BBD0),
      Color(0xFFD1C4E9),
      Color(0xFFFFF9C4),
      Color(0xFFB2DFDB),
      Color(0xFFFFCCBC),
    )

  private val BUBBLE_PALETTE_DARK =
    listOf(
      Color(0xFF5D4037),
      Color(0xFF01579B),
      Color(0xFF1B5E20),
      Color(0xFF880E4F),
      Color(0xFF311B92),
      Color(0xFF827717),
      Color(0xFF004D40),
      Color(0xFFBF360C),
    )

  /**
   * 由稳定 id 派生气泡底色。
   *
   * 用 id 而不是列表下标：否则删掉一个分类会让后面所有分类换色。
   */
  @Composable
  fun bubbleColor(seed: Long): Color {
    val palette = if (isSystemInDarkTheme()) BUBBLE_PALETTE_DARK else BUBBLE_PALETTE
    val index = ((seed % palette.size) + palette.size).toInt() % palette.size
    return palette[index]
  }
}
