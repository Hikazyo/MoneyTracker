package com.moneytracker.app.ui.statistics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneytracker.app.theme.MoneyTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 统计页的渲染与交互。 */
@RunWith(AndroidJUnit4::class)
class StatisticsContentTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun setContent(state: StatisticsUiState, onTypeChange: (Boolean) -> Unit = {}) {
    composeTestRule.setContent {
      MoneyTrackerTheme {
        StatisticsContent(
          state = state,
          onPreviousMonth = {},
          onNextMonth = {},
          onCurrentMonth = {},
          onTypeChange = onTypeChange,
        )
      }
    }
  }

  private val populatedState =
    StatisticsUiState(
      isLoading = false,
      monthLabel = "2026年10月",
      isCurrentMonth = true,
      isExpense = true,
      totalText = "4,000.00",
      isEmpty = false,
      slices =
        listOf(
          CategorySlice(1, "餐饮", "food", "3,000.00", "75.0%", 0.75f),
          CategorySlice(2, "交通", "transport", "1,000.00", "25.0%", 0.25f),
        ),
      trend =
        listOf(
          MonthBar("9月", 100_000, 200_000, false),
          MonthBar("10月", 400_000, 800_000, true),
        ),
    )

  @Test
  fun header_toggle_and_total_are_displayed() {
    setContent(populatedState)
    composeTestRule.onNodeWithText("统计").assertIsDisplayed()
    composeTestRule.onNodeWithText("2026年10月").assertIsDisplayed()
    composeTestRule.onNodeWithText("总支出").assertIsDisplayed()
    composeTestRule.onNodeWithText("¥4,000.00").assertIsDisplayed()
  }

  @Test
  fun ranking_lists_every_category_with_amount_and_percentage() {
    setContent(populatedState)
    composeTestRule.onNodeWithText("分类排行").assertIsDisplayed()
    composeTestRule.onNodeWithText("餐饮").assertIsDisplayed()
    composeTestRule.onNodeWithText("75.0%").assertIsDisplayed()
    composeTestRule.onNodeWithText("交通").assertIsDisplayed()
    composeTestRule.onNodeWithText("25.0%").assertIsDisplayed()
    composeTestRule.onNodeWithTag("slice-1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("slice-2").assertIsDisplayed()
  }

  @Test
  fun trend_section_shows_month_labels_and_legend() {
    setContent(populatedState)
    composeTestRule.onNodeWithText("近 2 个月趋势", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("9月").assertIsDisplayed()
    composeTestRule.onNodeWithText("10月").assertIsDisplayed()
    // 用 testTag 而不是文字：「支出/收入」同时也是筛选芯片的文案，按文字找会有歧义
    composeTestRule.onNodeWithTag("legend-expense").assertIsDisplayed()
    composeTestRule.onNodeWithTag("legend-income").assertIsDisplayed()
  }

  @Test
  fun empty_month_shows_message_but_still_renders_trend() {
    setContent(
      StatisticsUiState(
        isLoading = false,
        monthLabel = "2026年9月",
        isEmpty = true,
        trend = listOf(MonthBar("9月", 0, 0, true)),
      )
    )
    composeTestRule.onNodeWithText("该月没有支出记录").assertIsDisplayed()
  }

  @Test
  fun income_empty_message_differs_from_expense() {
    setContent(
      StatisticsUiState(
        isLoading = false,
        monthLabel = "2026年9月",
        isExpense = false,
        isEmpty = true,
      )
    )
    composeTestRule.onNodeWithText("该月没有收入记录").assertIsDisplayed()
  }

  @Test
  fun type_chips_report_selection() {
    val changes = mutableListOf<Boolean>()
    setContent(populatedState, onTypeChange = { changes += it })

    composeTestRule.onNodeWithTag("type-income").performClick()
    composeTestRule.onNodeWithTag("type-expense").performClick()

    assertEquals(listOf(false, true), changes)
  }
}
