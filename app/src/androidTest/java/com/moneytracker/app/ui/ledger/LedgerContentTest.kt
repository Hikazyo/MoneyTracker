package com.moneytracker.app.ui.ledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneytracker.app.theme.MoneyTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 账单列表页的渲染与交互（用无状态 content，便于直接喂各种状态）。 */
@RunWith(AndroidJUnit4::class)
class LedgerContentTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun setContent(state: LedgerUiState, onEditEntry: (Long) -> Unit = {}) {
    composeTestRule.setContent {
      MoneyTrackerTheme {
        LedgerContent(
          state = state,
          onPreviousMonth = {},
          onNextMonth = {},
          onCurrentMonth = {},
          onAddEntry = {},
          onEditEntry = onEditEntry,
        )
      }
    }
  }

  private val twoDayState =
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
            summary = "支出 32.50",
            entries = listOf(EntryRow(1, "餐饮", "food", "午饭", "12:30", "-32.50", true)),
          ),
          DayGroup(
            dayLabel = "昨天",
            summary = "收入 50.00",
            entries = listOf(EntryRow(2, "工资", "salary", null, "10:00", "+50.00", false)),
          ),
        ),
    )

  @Test
  fun month_and_summary_are_displayed() {
    setContent(twoDayState)
    composeTestRule.onNodeWithText("2026年10月").assertIsDisplayed()
    composeTestRule.onNodeWithText("¥1,234.50").assertIsDisplayed()
    composeTestRule.onNodeWithText("¥8,000.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("+6,765.50").assertIsDisplayed()
  }

  @Test
  fun day_groups_and_entries_are_displayed() {
    setContent(twoDayState)
    composeTestRule.onNodeWithText("今天").assertIsDisplayed()
    composeTestRule.onNodeWithText("昨天").assertIsDisplayed()
    composeTestRule.onNodeWithText("餐饮").assertIsDisplayed()
    composeTestRule.onNodeWithText("-32.50").assertIsDisplayed()
    composeTestRule.onNodeWithText("+50.00").assertIsDisplayed()
  }

  @Test
  fun note_is_shown_when_present() {
    setContent(twoDayState)
    composeTestRule.onNodeWithText("午饭").assertIsDisplayed()
  }

  @Test
  fun tapping_an_entry_invokes_edit_callback_with_its_id() {
    var tappedId: Long? = null
    setContent(twoDayState, onEditEntry = { tappedId = it })

    composeTestRule.onNodeWithText("餐饮").performClick()

    assertEquals(1L, tappedId)
  }

  @Test
  fun empty_state_is_shown_when_month_has_no_entries() {
    setContent(LedgerUiState(isLoading = false, monthLabel = "2026年9月", isEmpty = true))
    composeTestRule.onNodeWithText("这个月还没有记账", substring = true).assertIsDisplayed()
  }

  @Test
  fun error_state_is_shown_when_loading_failed() {
    setContent(LedgerUiState(isLoading = false, errorMessage = "数据库损坏"))
    composeTestRule.onNodeWithText("数据库损坏", substring = true).assertIsDisplayed()
  }
}
