package com.moneytracker.app.ui.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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

/** 「记一笔 / 编辑账目」页的渲染与键盘接线。 */
@RunWith(AndroidJUnit4::class)
class EditEntryContentTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val chips =
    listOf(
      CategoryChip(1, "餐饮", "food", true),
      CategoryChip(2, "交通", "transport", false),
      CategoryChip(3, "购物", "shopping", false),
    )

  private fun state(
    isEditing: Boolean = false,
    isExpense: Boolean = true,
    amountDisplay: String = "32.50",
    canSave: Boolean = true,
    note: String = "",
  ) = EditEntryUiState(
    isEditing = isEditing,
    isExpense = isExpense,
    amountDisplay = amountDisplay,
    note = note,
    dateLabel = "2026年10月10日",
    categories = chips,
    canSave = canSave,
  )

  private fun setContent(
    uiState: EditEntryUiState = state(),
    onDigit: (Char) -> Unit = {},
    onDot: () -> Unit = {},
    onBackspace: () -> Unit = {},
    onClear: () -> Unit = {},
    onSave: () -> Unit = {},
    onDelete: () -> Unit = {},
  ) {
    composeTestRule.setContent {
      MoneyTrackerTheme {
        EditEntryContent(
          state = uiState,
          onBack = {},
          onDigit = onDigit,
          onDot = onDot,
          onBackspace = onBackspace,
          onClear = onClear,
          onTypeChange = {},
          onCategorySelect = {},
          onNoteChange = {},
          onDateSelected = {},
          onSave = onSave,
          onDelete = onDelete,
        )
      }
    }
  }

  @Test
  fun new_entry_shows_title_amount_and_categories() {
    setContent()
    composeTestRule.onNodeWithText("记一笔").assertIsDisplayed()
    composeTestRule.onNodeWithTag("amount-display").assertIsDisplayed()
    composeTestRule.onNodeWithText("餐饮").assertIsDisplayed()
    composeTestRule.onNodeWithText("交通").assertIsDisplayed()
  }

  @Test
  fun editing_mode_shows_different_title_and_delete_action() {
    setContent(state(isEditing = true))
    composeTestRule.onNodeWithText("编辑账目").assertIsDisplayed()
    composeTestRule.onNodeWithTag("delete-button").assertIsDisplayed()
  }

  @Test
  fun new_entry_has_no_delete_action() {
    setContent(state(isEditing = false))
    composeTestRule.onNodeWithTag("delete-button").assertDoesNotExist()
  }

  @Test
  fun date_and_note_are_rendered() {
    setContent(state(note = "午饭"))
    composeTestRule.onNodeWithText("2026年10月10日").assertIsDisplayed()
    composeTestRule.onNodeWithText("午饭").assertIsDisplayed()
  }

  @Test
  fun digit_key_passes_the_correct_character() {
    val pressed = mutableListOf<Char>()
    setContent(onDigit = { pressed += it })

    composeTestRule.onNodeWithTag("key-1").performClick()
    composeTestRule.onNodeWithTag("key-7").performClick()
    composeTestRule.onNodeWithTag("key-0").performClick()

    assertEquals(listOf('1', '7', '0'), pressed)
  }

  @Test
  fun dot_key_invokes_dot_callback() {
    var dots = 0
    setContent(onDot = { dots++ })
    composeTestRule.onNodeWithTag("key-.").performClick()
    assertEquals(1, dots)
  }

  @Test
  fun backspace_and_clear_have_their_own_callbacks() {
    var backspaces = 0
    var clears = 0
    setContent(onBackspace = { backspaces++ }, onClear = { clears++ })

    composeTestRule.onNodeWithTag("key-⌫").performClick()
    composeTestRule.onNodeWithTag("key-清空").performClick()

    assertEquals(1, backspaces)
    assertEquals(1, clears)
  }

  @Test
  fun save_button_is_disabled_when_state_says_cannot_save() {
    setContent(state(amountDisplay = "0.00", canSave = false))
    composeTestRule.onNodeWithTag("save-button").assertIsNotEnabled()
  }

  @Test
  fun save_button_is_enabled_and_clickable_when_can_save() {
    var saves = 0
    setContent(state(canSave = true), onSave = { saves++ })

    composeTestRule.onNodeWithTag("save-button").assertIsEnabled()
    composeTestRule.onNodeWithTag("save-button").performClick()
    assertEquals(1, saves)
  }
}
