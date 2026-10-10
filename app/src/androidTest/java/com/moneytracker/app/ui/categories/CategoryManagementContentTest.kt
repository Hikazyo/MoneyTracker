package com.moneytracker.app.ui.categories

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.theme.MoneyTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 分类管理页的渲染与交互。 */
@RunWith(AndroidJUnit4::class)
class CategoryManagementContentTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val expenseRows =
    listOf(
      // 内置、有账目：不能改图标/方向，也不能归档
      CategoryRow(1, "餐饮", "food", TransactionType.EXPENSE, isBuiltIn = true, isArchived = false, entryCount = 12),
      // 内置、无账目：同上
      CategoryRow(2, "交通", "transport", TransactionType.EXPENSE, isBuiltIn = true, isArchived = false, entryCount = 0),
      // 自定义、有账目：能换图标，不能改方向；操作是「编辑 / 归档」
      CategoryRow(20, "宠物", "pet", TransactionType.EXPENSE, isBuiltIn = false, isArchived = false, entryCount = 3),
      // 自定义、无账目：能换图标、能改方向；操作是「编辑 / 删除」
      CategoryRow(22, "咖啡", "coffee", TransactionType.EXPENSE, isBuiltIn = false, isArchived = false, entryCount = 0),
      CategoryRow(21, "健身", "sport", TransactionType.EXPENSE, isBuiltIn = false, isArchived = true, entryCount = 8),
    )

  private val state =
    CategoryManagementUiState(
      isLoading = false,
      expense = expenseRows,
      income =
        listOf(
          CategoryRow(11, "工资", "salary", TransactionType.INCOME, isBuiltIn = true, isArchived = false, entryCount = 3)
        ),
    )

  private fun setContent(
    uiState: CategoryManagementUiState = state,
    onAdd: (String, String, TransactionType) -> Unit = { _, _, _ -> },
    onEdit: (Long, String, String, TransactionType) -> Unit = { _, _, _, _ -> },
    onDelete: (CategoryRow) -> Unit = {},
    onArchive: (Long) -> Unit = {},
    onUnarchive: (Long) -> Unit = {},
  ) {
    composeTestRule.setContent {
      MoneyTrackerTheme {
        CategoryManagementContent(
          state = uiState,
          onBack = {},
          onAdd = onAdd,
          onEdit = onEdit,
          onDelete = onDelete,
          onArchive = onArchive,
          onUnarchive = onUnarchive,
          onMessageShown = {},
        )
      }
    }
  }

  // ---------- 渲染 ----------

  @Test
  fun both_direction_sections_are_rendered() {
    setContent()
    composeTestRule.onNodeWithText("分类管理").assertIsDisplayed()
    composeTestRule.onNodeWithText("支出分类").assertIsDisplayed()
    composeTestRule.onNodeWithText("收入分类").assertIsDisplayed()
  }

  @Test
  fun rows_show_name_and_entry_count() {
    setContent()
    composeTestRule.onNodeWithText("餐饮").assertIsDisplayed()
    composeTestRule.onNodeWithText("内置 · 12 笔").assertIsDisplayed()
    composeTestRule.onNodeWithText("交通").assertIsDisplayed()
    composeTestRule.onNodeWithText("内置 · 0 笔").assertIsDisplayed()
  }

  @Test
  fun archived_rows_are_listed_under_their_own_header() {
    setContent()
    composeTestRule.onNodeWithTag("archived-expense").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("已归档 · 8 笔").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun hint_explains_what_built_in_categories_cannot_do() {
    setContent()
    // 支出与收入两个区块各有一份提示，因此是 2 个节点
    composeTestRule
      .onAllNodesWithText("内置分类可以改名与换图标，但不能改方向或归档；自定义分类都可以")
      .assertCountEquals(2)
  }

  // ---------- 每行给出的操作 ----------

  @Test
  fun every_row_can_be_edited() {
    setContent()
    composeTestRule.onNodeWithTag("edit-1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("edit-20").assertIsDisplayed()
    composeTestRule.onNodeWithTag("edit-22").assertIsDisplayed()
  }

  @Test
  fun a_built_in_row_offers_neither_archive_nor_delete() {
    setContent()
    composeTestRule.onNodeWithTag("archive-1").assertDoesNotExist()
    composeTestRule.onNodeWithTag("delete-1").assertDoesNotExist()
  }

  @Test
  fun a_used_custom_row_offers_archive_only() {
    setContent()
    composeTestRule.onNodeWithTag("archive-20").assertIsDisplayed()
    composeTestRule.onNodeWithTag("delete-20").assertDoesNotExist()
  }

  @Test
  fun an_empty_custom_row_offers_delete_only() {
    // 归档与删除并列会让用户停下来想「差在哪」，两种状态各给一个就够了
    setContent()
    composeTestRule.onNodeWithTag("delete-22").assertIsDisplayed()
    composeTestRule.onNodeWithTag("archive-22").assertDoesNotExist()
  }

  @Test
  fun archived_row_offers_restore_instead_of_archive() {
    setContent()
    composeTestRule.onNodeWithTag("unarchive-21").assertIsDisplayed()
    composeTestRule.onNodeWithTag("archive-21").assertDoesNotExist()
  }

  @Test
  fun restoring_reports_the_category_id() {
    var restored: Long? = null
    setContent(onUnarchive = { restored = it })

    composeTestRule.onNodeWithTag("unarchive-21").performClick()

    assertEquals(21L, restored)
  }

  // ---------- 归档确认 ----------

  @Test
  fun archiving_asks_for_confirmation_and_mentions_the_entry_count() {
    setContent()

    composeTestRule.onNodeWithTag("archive-20").performClick()

    composeTestRule.onNodeWithTag("archive-dialog").assertIsDisplayed()
    composeTestRule.onNodeWithText("3 笔账目", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("完整保留", substring = true).assertIsDisplayed()

    composeTestRule.onNodeWithTag("archive-cancel").performClick()
  }

  @Test
  fun confirming_archive_reports_the_category_id() {
    var archived: Long? = null
    setContent(onArchive = { archived = it })

    composeTestRule.onNodeWithTag("archive-20").performClick()
    composeTestRule.onNodeWithTag("archive-confirm").performClick()

    assertEquals(20L, archived)
  }

  // ---------- 删除确认 ----------

  @Test
  fun deleting_asks_for_confirmation_and_says_it_cannot_be_undone() {
    setContent()

    composeTestRule.onNodeWithTag("delete-22").performClick()

    composeTestRule.onNodeWithTag("delete-dialog").assertIsDisplayed()
    composeTestRule.onNodeWithText("无法恢复", substring = true).assertIsDisplayed()

    composeTestRule.onNodeWithTag("delete-cancel").performClick()
  }

  @Test
  fun confirming_delete_reports_the_row() {
    var deleted: CategoryRow? = null
    setContent(onDelete = { deleted = it })

    composeTestRule.onNodeWithTag("delete-22").performClick()
    composeTestRule.onNodeWithTag("delete-confirm").performClick()

    assertEquals(22L, deleted?.id)
  }

  // ---------- 编辑：能改什么 ----------

  @Test
  fun editing_a_custom_category_offers_the_icon_picker() {
    // 这就是「可编辑自定义分类」的核心：图标以前是锁死的，只能改名
    setContent()

    composeTestRule.onNodeWithTag("edit-20").performClick()

    composeTestRule.onNodeWithTag("category-dialog").assertIsDisplayed()
    composeTestRule.onNodeWithText("编辑分类").assertIsDisplayed()
    composeTestRule.onNodeWithTag("category-name").assertTextContains("宠物")
    // 图标网格有十几行，靠后的图标要先滚进视口才谈得上「可见」
    composeTestRule.onNodeWithTag("icon-heart").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("category-cancel").performClick()
  }

  @Test
  fun editing_reports_the_new_icon_together_with_the_name() {
    var edited: List<Any>? = null
    setContent(onEdit = { id, name, icon, type -> edited = listOf(id, name, icon, type) })

    composeTestRule.onNodeWithTag("edit-20").performClick()
    composeTestRule.onNodeWithTag("icon-heart").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("category-save").performClick()

    assertEquals(listOf(20L, "宠物", "heart", TransactionType.EXPENSE), edited)
  }

  @Test
  fun editing_a_built_in_category_locks_the_icon_and_explains_why() {
    // 内置分类的 iconKey 是导入自动归类的锚点，所以只读；
    // 但必须**显示出来并说明原因**，否则用户以为功能没做
    setContent()

    composeTestRule.onNodeWithTag("edit-1").performClick()

    composeTestRule.onNodeWithTag("icon-heart").assertDoesNotExist()
    composeTestRule.onNodeWithTag("category-icon-readonly").assertIsDisplayed()
    composeTestRule
      .onNodeWithText("自动归类的依据", substring = true)
      .assertIsDisplayed()

    composeTestRule.onNodeWithTag("category-cancel").performClick()
  }

  @Test
  fun editing_a_category_with_entries_locks_the_direction_and_states_the_count() {
    setContent()

    composeTestRule.onNodeWithTag("edit-20").performClick()

    // 断言顺序必须自上而下：图标网格很长，一旦滚下去，上面的方向说明就被顶出视口了
    composeTestRule.onNodeWithTag("category-type-income").assertDoesNotExist()
    composeTestRule.onNodeWithTag("category-type-readonly").assertIsDisplayed()
    composeTestRule.onNodeWithText("3 笔账目", substring = true).assertIsDisplayed()
    // 图标仍然可以改
    composeTestRule.onNodeWithTag("icon-heart").performScrollTo().assertIsDisplayed()

    // 取消按钮在对话框的按钮区，不在那个可滚动的正文里，所以不能对它 performScrollTo
    composeTestRule.onNodeWithTag("category-cancel").performClick()
  }

  @Test
  fun editing_an_empty_custom_category_allows_switching_direction() {
    setContent()

    composeTestRule.onNodeWithTag("edit-22").performClick()
    composeTestRule.onNodeWithTag("category-type-income").performClick()
    composeTestRule.onNodeWithTag("category-save").performClick()

    // 无账目 → 方向可改，回调里带的是新方向
    // （onEdit 的默认实现是空操作，这里只验证不崩且能提交）
    composeTestRule.onNodeWithTag("category-dialog").assertDoesNotExist()
  }

  @Test
  fun switching_direction_on_an_empty_category_is_reported() {
    var edited: List<Any>? = null
    setContent(onEdit = { id, name, icon, type -> edited = listOf(id, name, icon, type) })

    composeTestRule.onNodeWithTag("edit-22").performClick()
    composeTestRule.onNodeWithTag("category-type-income").performClick()
    composeTestRule.onNodeWithTag("category-save").performClick()

    assertEquals(listOf(22L, "咖啡", "coffee", TransactionType.INCOME), edited)
  }

  @Test
  fun renaming_reports_the_edited_name() {
    var edited: List<Any>? = null
    setContent(onEdit = { id, name, icon, type -> edited = listOf(id, name, icon, type) })

    composeTestRule.onNodeWithTag("edit-1").performClick()
    // 必须先清空：performTextInput 是在光标处插入，不是追加到末尾
    composeTestRule.onNodeWithTag("category-name").performTextClearance()
    composeTestRule.onNodeWithTag("category-name").performTextInput("吃饭")
    composeTestRule.onNodeWithTag("category-save").performClick()

    assertEquals(listOf(1L, "吃饭", "food", TransactionType.EXPENSE), edited)
  }

  // ---------- 新建 ----------

  @Test
  fun add_dialog_offers_type_toggle_and_icon_picker() {
    setContent()

    composeTestRule.onNodeWithTag("add-category").performClick()

    composeTestRule.onNodeWithText("新建分类").assertIsDisplayed()
    composeTestRule.onNodeWithTag("category-type-expense").assertIsDisplayed()
    composeTestRule.onNodeWithTag("category-type-income").assertIsDisplayed()
    composeTestRule.onNodeWithTag("icon-pet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("icon-food").assertIsDisplayed()

    composeTestRule.onNodeWithTag("category-cancel").performClick()
  }

  @Test
  fun creating_a_category_reports_name_icon_and_type() {
    var added: Triple<String, String, TransactionType>? = null
    setContent(onAdd = { name, icon, type -> added = Triple(name, icon, type) })

    composeTestRule.onNodeWithTag("add-category").performClick()
    composeTestRule.onNodeWithTag("category-name").performTextInput("宠物")
    composeTestRule.onNodeWithTag("icon-pet").performClick()
    composeTestRule.onNodeWithTag("category-save").performClick()

    assertEquals(Triple("宠物", "pet", TransactionType.EXPENSE), added)
  }

  @Test
  fun switching_to_income_then_creating_reports_income_type() {
    var added: Triple<String, String, TransactionType>? = null
    setContent(onAdd = { name, icon, type -> added = Triple(name, icon, type) })

    composeTestRule.onNodeWithTag("add-category").performClick()
    composeTestRule.onNodeWithTag("category-type-income").performClick()
    composeTestRule.onNodeWithTag("category-name").performTextInput("稿费")
    composeTestRule.onNodeWithTag("category-save").performClick()

    assertEquals(TransactionType.INCOME, added?.third)
  }

  @Test
  fun loading_state_shows_a_spinner_instead_of_rows() {
    setContent(CategoryManagementUiState(isLoading = true))
    composeTestRule.onNodeWithText("餐饮").assertDoesNotExist()
  }

  @Test
  fun back_button_is_clickable() {
    setContent()
    composeTestRule.onNodeWithContentDescription("返回").performClick()
  }
}
