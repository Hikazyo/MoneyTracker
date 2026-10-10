package com.moneytracker.app.ui.budget

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

/** 预算页的渲染与交互。 */
@RunWith(AndroidJUnit4::class)
class BudgetContentTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun setContent(
    state: BudgetUiState,
    onSaveTotal: (Long?) -> Unit = {},
    onSaveCategory: (Long, Long?) -> Unit = { _, _ -> },
    onConfirmOverBudget: () -> Unit = {},
    onDismissOverBudget: () -> Unit = {},
  ) {
    composeTestRule.setContent {
      MoneyTrackerTheme {
        BudgetContent(
          state = state,
          onPreviousMonth = {},
          onNextMonth = {},
          onCurrentMonth = {},
          onSaveTotal = onSaveTotal,
          onSaveCategory = onSaveCategory,
          onConfirmOverBudget = onConfirmOverBudget,
          onDismissOverBudget = onDismissOverBudget,
          onMessageShown = {},
        )
      }
    }
  }

  private val rows =
    listOf(
      CategoryBudgetRow(1, "餐饮", "food", 50_000L, "500.00", 32_000L, "320.00", 0.64f, false, "剩余 180.00"),
      CategoryBudgetRow(2, "交通", "transport", 10_000L, "100.00", 32_000L, "320.00", 3.2f, true, "超支 220.00"),
      CategoryBudgetRow(3, "购物", "shopping", null, "未设置", 0L, "0.00", null, false, null),
    )

  private val populatedState =
    BudgetUiState(
      isLoading = false,
      monthLabel = "2026年10月",
      isCurrentMonth = true,
      totalBudgetMinor = 100_000L,
      totalBudgetText = "1,000.00",
      // 刻意用与各分类不同的金额，避免「已用 X」在总计与分类行里重名导致断言歧义
      totalSpentText = "440.00",
      totalRatio = 0.44f,
      totalRemainingLabel = "剩余 560.00",
      totalIsOver = false,
      categoryRows = rows,
      categoryBudgetSumMinor = 60_000L,
      categoryBudgetSumText = "600.00",
      categorySumExceedsTotal = false,
    )

  @Test
  fun total_budget_value_and_remaining_are_displayed() {
    setContent(populatedState)
    composeTestRule.onNodeWithText("月度总预算").assertIsDisplayed()
    composeTestRule.onNodeWithTag("total-budget-value").assertIsDisplayed()
    composeTestRule.onNodeWithText("¥1,000.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("已用 440.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("剩余 560.00").assertIsDisplayed()
  }

  @Test
  fun unset_total_budget_shows_prompt_and_set_button() {
    setContent(BudgetUiState(isLoading = false, monthLabel = "2026年10月", categoryRows = rows))
    composeTestRule.onNodeWithText("还没有设置总预算").assertIsDisplayed()
    composeTestRule.onNodeWithTag("edit-total-budget").assertIsDisplayed()
  }

  @Test
  fun category_rows_show_budget_and_placeholder_when_unset() {
    setContent(populatedState)
    composeTestRule.onNodeWithText("分类预算").assertIsDisplayed()
    composeTestRule.onNodeWithText("500.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("未设置").assertIsDisplayed()
    composeTestRule.onNodeWithTag("category-row-1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("category-row-3").assertIsDisplayed()
  }

  @Test
  fun over_budget_row_shows_over_label() {
    setContent(populatedState)
    composeTestRule.onNodeWithText("超支 220.00").assertIsDisplayed()
  }

  @Test
  fun warning_banner_is_shown_when_category_sum_exceeds_total() {
    setContent(
      populatedState.copy(
        categoryBudgetSumText = "1,200.00",
        categorySumExceedsTotal = true,
      )
    )
    composeTestRule.onNodeWithTag("budget-warning").assertIsDisplayed()
    composeTestRule.onNodeWithText("已超过总预算", substring = true).assertIsDisplayed()
  }

  @Test
  fun warning_banner_is_absent_when_within_total() {
    setContent(populatedState)
    composeTestRule.onNodeWithTag("budget-warning").assertDoesNotExist()
  }

  @Test
  fun tapping_a_category_row_opens_the_input_dialog() {
    setContent(populatedState)
    composeTestRule.onNodeWithTag("budget-input").assertDoesNotExist()

    composeTestRule.onNodeWithTag("category-row-3").performClick()

    composeTestRule.onNodeWithTag("budget-input").assertIsDisplayed()
    composeTestRule.onNodeWithText("购物预算").assertIsDisplayed()

    // 收尾关掉弹窗：留着弹窗会让 Activity 停在 PAUSED，测试规则等不到 DESTROYED
    composeTestRule.onNodeWithTag("budget-cancel").performClick()
  }

  @Test
  fun confirming_a_prefilled_row_round_trips_the_amount() {
    // 回填文案用 Money.format（不带千分位），必须能原样 parse 回来
    var savedId: Long? = null
    var savedAmount: Long? = null
    setContent(populatedState, onSaveCategory = { id, amount -> savedId = id; savedAmount = amount })

    composeTestRule.onNodeWithTag("category-row-1").performClick()
    composeTestRule.onNodeWithTag("budget-confirm").performClick()

    assertEquals(1L, savedId)
    assertEquals(50_000L, savedAmount)
  }

  @Test
  fun empty_amount_shows_validation_error_instead_of_saving() {
    var saved = false
    setContent(populatedState, onSaveCategory = { _, _ -> saved = true })

    // 购物这一行没有预算，输入框初始为空
    composeTestRule.onNodeWithTag("category-row-3").performClick()
    composeTestRule.onNodeWithTag("budget-confirm").performClick()

    composeTestRule.onNodeWithText("请输入大于 0 的金额").assertIsDisplayed()
    assertEquals(false, saved)

    composeTestRule.onNodeWithTag("budget-cancel").performClick()
  }

  @Test
  fun clear_button_only_shown_when_a_budget_exists() {
    setContent(populatedState)
    // 未设预算的行不应有「清除」按钮
    composeTestRule.onNodeWithTag("category-row-3").performClick()
    composeTestRule.onNodeWithTag("budget-clear").assertDoesNotExist()
    composeTestRule.onNodeWithTag("budget-cancel").performClick()
  }

  @Test
  fun clearing_an_existing_budget_passes_null() {
    var cleared: Pair<Long, Long?>? = null
    setContent(populatedState, onSaveCategory = { id, amount -> cleared = id to amount })

    composeTestRule.onNodeWithTag("category-row-1").performClick()
    composeTestRule.onNodeWithTag("budget-clear").performClick()

    assertEquals(1L to null, cleared)
  }

  @Test
  fun pending_over_budget_shows_confirmation_dialog() {
    setContent(
      populatedState.copy(
        pending =
          PendingOverBudgetSave(
            categoryId = 2L,
            requestedAmountMinor = 50_000L,
            totalMinor = 100_000L,
            requestedSumMinor = 130_000L,
          )
      )
    )
    composeTestRule.onNodeWithTag("over-budget-dialog").assertIsDisplayed()
    composeTestRule.onNodeWithText("分类预算之和超过总预算").assertIsDisplayed()
    composeTestRule.onNodeWithText("仍然保存").assertIsDisplayed()

    composeTestRule.onNodeWithTag("over-budget-cancel").performClick()
  }

  @Test
  fun confirming_the_over_budget_dialog_reports_it() {
    var confirmed = 0
    setContent(
      populatedState.copy(
        pending =
          PendingOverBudgetSave(
            categoryId = 2L,
            requestedAmountMinor = 50_000L,
            totalMinor = 100_000L,
            requestedSumMinor = 130_000L,
          )
      ),
      onConfirmOverBudget = { confirmed++ },
    )
    composeTestRule.onNodeWithTag("over-budget-confirm").performClick()
    assertEquals(1, confirmed)
  }

  @Test
  fun dismissing_the_over_budget_dialog_reports_it() {
    var dismissed = 0
    setContent(
      populatedState.copy(
        pending =
          PendingOverBudgetSave(
            categoryId = 2L,
            requestedAmountMinor = 50_000L,
            totalMinor = 100_000L,
            requestedSumMinor = 130_000L,
          )
      ),
      onDismissOverBudget = { dismissed++ },
    )
    composeTestRule.onNodeWithTag("over-budget-cancel").performClick()
    assertEquals(1, dismissed)
  }

  @Test
  fun over_budget_dialog_is_absent_without_pending_save() {
    setContent(populatedState)
    composeTestRule.onNodeWithTag("over-budget-dialog").assertDoesNotExist()
  }
}
