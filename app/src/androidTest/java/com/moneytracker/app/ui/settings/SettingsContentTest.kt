package com.moneytracker.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneytracker.app.data.backup.BackupCodec
import com.moneytracker.app.data.backup.BackupCategory
import com.moneytracker.app.data.backup.BackupFile
import com.moneytracker.app.data.backup.BackupTransaction
import com.moneytracker.app.theme.MoneyTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 设置页的渲染与交互。 */
@RunWith(AndroidJUnit4::class)
class SettingsContentTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun setContent(
    state: SettingsUiState = SettingsUiState(),
    onExportCsv: () -> Unit = {},
    onCreateBackup: () -> Unit = {},
    onPickBackup: () -> Unit = {},
    onConfirmRestore: () -> Unit = {},
    onDismissRestore: () -> Unit = {},
    importBusy: Boolean = false,
    onPickImportFile: () -> Unit = {},
    onOpenCategories: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
  ) {
    composeTestRule.setContent {
      MoneyTrackerTheme {
        SettingsContent(
          state = state,
          onExportCsv = onExportCsv,
          onCreateBackup = onCreateBackup,
          onPickBackup = onPickBackup,
          onConfirmRestore = onConfirmRestore,
          onDismissRestore = onDismissRestore,
          onMessageShown = {},
          importBusy = importBusy,
          onPickImportFile = onPickImportFile,
          onOpenCategories = onOpenCategories,
          onOpenAbout = onOpenAbout,
        )
      }
    }
  }

  private val pendingBackup =
    BackupFile(
      schemaVersion = BackupCodec.CURRENT_SCHEMA_VERSION,
      exportedAt = 1_700_000_000_000L,
      categories =
        listOf(BackupCategory(1, "餐饮", "food", "EXPENSE", 0, isBuiltIn = true, isArchived = false)),
      transactions =
        listOf(
          BackupTransaction(1, 3200, "EXPENSE", 1, "午饭", 1L, 1L, 1L),
          BackupTransaction(2, 880, "EXPENSE", 1, null, 2L, 1L, 1L),
        ),
    )

  @Test
  fun all_three_actions_are_listed() {
    setContent()
    composeTestRule.onNodeWithText("导出 CSV").assertIsDisplayed()
    composeTestRule.onNodeWithText("创建备份").assertIsDisplayed()
    composeTestRule.onNodeWithText("从备份恢复").assertIsDisplayed()
  }

  @Test
  fun bill_import_action_is_listed_and_clickable() {
    var picks = 0
    setContent(onPickImportFile = { picks++ })

    composeTestRule.onNodeWithText("导入账单").assertIsDisplayed()
    composeTestRule.onNodeWithTag("import-bill").performClick()

    assertEquals(1, picks)
  }

  @Test
  fun import_description_explains_the_encoding_handling() {
    setContent()
    composeTestRule.onNodeWithText("GBK", substring = true).assertIsDisplayed()
  }

  @Test
  fun import_button_is_disabled_while_importing() {
    setContent(SettingsUiState(isBusy = false), importBusy = true)
    composeTestRule.onNodeWithTag("import-bill").assertIsNotEnabled()
  }

  @Test
  fun category_management_entry_is_listed_and_clickable() {
    var opened = 0
    setContent(onOpenCategories = { opened++ })

    composeTestRule.onNodeWithText("分类管理").assertIsDisplayed()
    composeTestRule.onNodeWithTag("manage-categories").performClick()

    assertEquals(1, opened)
  }

  @Test
  fun about_entry_is_listed_and_clickable() {
    var opened = 0
    setContent(onOpenAbout = { opened++ })

    // 「关于与隐私」是最后一张卡片，首屏之外，先滚动到它
    composeTestRule.onNodeWithText("关于与隐私").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("open-about").performScrollTo().performClick()

    assertEquals(1, opened)
  }

  @Test
  fun export_description_mentions_the_bom_reason() {
    // 说明文案要能解释「为什么导出不会乱码」，否则用户遇到问题无从判断
    setContent()
    composeTestRule.onNodeWithText("UTF-8 BOM", substring = true).assertIsDisplayed()
  }

  @Test
  fun each_action_reports_its_click() {
    var csv = 0
    var backup = 0
    var pick = 0
    setContent(
      onExportCsv = { csv++ },
      onCreateBackup = { backup++ },
      onPickBackup = { pick++ },
    )

    composeTestRule.onNodeWithTag("export-csv").performClick()
    composeTestRule.onNodeWithTag("create-backup").performClick()
    composeTestRule.onNodeWithTag("restore-backup").performClick()

    assertEquals(1, csv)
    assertEquals(1, backup)
    assertEquals(1, pick)
  }

  @Test
  fun buttons_are_disabled_while_busy() {
    setContent(SettingsUiState(isBusy = true))
    composeTestRule.onNodeWithTag("export-csv").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("create-backup").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("restore-backup").assertIsNotEnabled()
  }

  @Test
  fun buttons_are_enabled_when_idle() {
    setContent(SettingsUiState(isBusy = false))
    composeTestRule.onNodeWithTag("export-csv").assertIsEnabled()
  }

  @Test
  fun pending_restore_shows_a_destructive_confirmation_with_counts() {
    setContent(SettingsUiState(pendingRestore = pendingBackup))

    composeTestRule.onNodeWithTag("restore-dialog").assertIsDisplayed()
    composeTestRule.onNodeWithText("确认恢复").assertIsDisplayed()
    composeTestRule.onNodeWithText("1 个分类", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("2 笔账目", substring = true).assertIsDisplayed()

    // 收尾关掉弹窗，避免 Activity 停在 PAUSED 导致测试规则等不到 DESTROYED
    composeTestRule.onNodeWithTag("restore-cancel").performClick()
  }

  @Test
  fun confirming_the_dialog_reports_it() {
    var confirmed = 0
    setContent(SettingsUiState(pendingRestore = pendingBackup), onConfirmRestore = { confirmed++ })

    composeTestRule.onNodeWithTag("restore-confirm").performClick()

    assertEquals(1, confirmed)
  }

  @Test
  fun cancelling_the_dialog_reports_it() {
    var dismissed = 0
    setContent(SettingsUiState(pendingRestore = pendingBackup), onDismissRestore = { dismissed++ })

    composeTestRule.onNodeWithTag("restore-cancel").performClick()

    assertEquals(1, dismissed)
  }

  @Test
  fun dialog_is_absent_without_a_pending_restore() {
    setContent(SettingsUiState())
    composeTestRule.onNodeWithTag("restore-dialog").assertDoesNotExist()
  }
}
