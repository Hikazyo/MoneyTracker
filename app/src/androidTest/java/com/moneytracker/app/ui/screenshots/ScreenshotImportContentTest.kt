package com.moneytracker.app.ui.screenshots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.theme.MoneyTrackerTheme
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 截图记账页的界面测试。
 *
 * 只测「界面把状态画对了、点击发出了正确的回调」，不碰识别与仓库——
 * 那两层分别由 ScreenshotParserTest 与仓库测试覆盖。
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotImportContentTest {

  @get:Rule val composeRule = createComposeRule()

  private val zone = ZoneId.systemDefault()

  private val foodCategory =
    CategoryEntity(id = 1, name = "餐饮", iconKey = "food", type = TransactionType.EXPENSE)

  @Test
  fun empty_state_offers_the_picker() {
    setContent(ScreenshotImportState())

    composeRule.onNodeWithText("从截图记账").assertIsDisplayed()
    composeRule.onNodeWithTag("screenshot-pick").assertIsDisplayed()
    // 没有内容时不该出现底部操作栏
    composeRule.onNodeWithTag("screenshot-import").assertDoesNotExist()
  }

  @Test
  fun rows_show_merchant_amount_and_warnings() {
    setContent(stateWith(row(selected = true, suspicious = true)))

    composeRule.onNodeWithText("秋夫").assertIsDisplayed()
    composeRule.onNodeWithText("-¥13.80").assertIsDisplayed()
    composeRule.onNodeWithText("疑似资金划转", substring = true).assertIsDisplayed()
    composeRule.onNodeWithTag("screenshot-import").assertIsDisplayed()
    composeRule.onNodeWithText("导入 1 笔").assertIsDisplayed()
  }

  @Test
  fun unselected_rows_are_not_counted() {
    setContent(stateWith(row(selected = false)))

    composeRule.onNodeWithText("导入 0 笔").assertIsDisplayed()
  }

  @Test
  fun checkbox_click_reports_the_row_id() {
    var toggled: String? = null
    setContent(stateWith(row(selected = true)), onToggle = { toggled = it })

    composeRule.onNodeWithTag("screenshot-row-shot-1").performClick()

    assertEquals("shot-1", toggled)
  }

  @Test
  fun import_button_emits_import() {
    var imported = false
    setContent(stateWith(row(selected = true)), onImport = { imported = true })

    composeRule.onNodeWithTag("screenshot-import").performClick()

    assertTrue(imported)
  }

  // ---------- helpers ----------

  private fun stateWith(vararg rows: ScreenshotRow) =
    ScreenshotImportState(
      rows = rows.toList(),
      categories = listOf(foodCategory),
    )

  private fun row(selected: Boolean, suspicious: Boolean = false) =
    ScreenshotRow(
      id = "shot-1",
      imageName = "sample1.jpg",
      merchant = "秋夫",
      categoryLabel = null,
      occurredAtMillis =
        LocalDateTime.of(2026, 10, 28, 11, 47).atZone(zone).toInstant().toEpochMilli(),
      type = TransactionType.EXPENSE,
      amountMinor = 1380,
      categoryId = foodCategory.id,
      selected = selected,
      suspicious = suspicious,
      timeUncertain = false,
      directionUncertain = false,
    )

  private fun setContent(
    state: ScreenshotImportState,
    onToggle: (String) -> Unit = {},
    onImport: () -> Unit = {},
  ) {
    composeRule.setContent {
      MoneyTrackerTheme {
        ScreenshotImportContent(
          state = state,
          onBack = {},
          onPick = {},
          onToggle = onToggle,
          onSelectAll = {},
          onSetCategory = { _, _ -> },
          onSetDate = { _, _ -> },
          onImport = onImport,
          onClear = {},
          onMessageShown = {},
        )
      }
    }
  }
}
