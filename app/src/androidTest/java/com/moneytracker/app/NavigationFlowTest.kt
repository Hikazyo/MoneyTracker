package com.moneytracker.app

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneytracker.app.data.db.MoneyTrackerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 导航流程集成测试：跑真实的 MainActivity、真实的仓库与数据库。
 *
 * **存在的意义**：M2 时出现过一个这类测试才能覆盖的 bug——Navigation3 1.0.1 不提供
 * 导航项级的 ViewModel 作用域，「记一笔」第二次进入时会因上一次的 `finished` 标记残留
 * 而立刻自己弹回。无状态的 content 测试完全测不到它，只有端到端流程能暴露。
 *
 * 因此这里专门覆盖「同一路由第二次进入」这个场景。
 */
@RunWith(AndroidJUnit4::class)
class NavigationFlowTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Before
  fun clearTransactions() = runBlocking {
    // 用与生产完全相同的建库入口，保证种子分类存在
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = MoneyTrackerDatabase.build(context)
    db.openHelper.writableDatabase.execSQL("DELETE FROM transactions")
    db.openHelper.writableDatabase.execSQL("DELETE FROM monthly_budgets")
    db.openHelper.writableDatabase.execSQL("DELETE FROM category_budgets")
    db.close()
  }

  private fun waitForEditScreen() {
    composeTestRule.waitUntil(5_000) {
      composeTestRule.onAllNodesWithTag("amount-display").fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun waitForLedger() {
    composeTestRule.waitUntil(5_000) {
      composeTestRule.onAllNodesWithTag("amount-display").fetchSemanticsNodes().isEmpty()
    }
  }

  /** 走一遍「记一笔」完整流程。 */
  private fun addEntry(digits: List<String>) {
    composeTestRule.onNodeWithTag("add-entry").performClick()
    waitForEditScreen()
    digits.forEach { composeTestRule.onNodeWithTag("key-$it").performClick() }
    composeTestRule.onNodeWithTag("save-button").performClick()
    waitForLedger()
  }

  @Test
  fun adding_two_entries_in_a_row_both_persist() {
    addEntry(listOf("1", "2"))
    // 关键一步：第二次进入编辑页。修复前这里会被上一次的 finished 标记立刻弹回。
    addEntry(listOf("3", "4"))

    composeTestRule.onAllNodesWithText("-12.00").assertCountEquals(1)
    composeTestRule.onAllNodesWithText("-34.00").assertCountEquals(1)
  }

  @Test
  fun edit_screen_starts_fresh_on_every_entry() {
    addEntry(listOf("5", "0"))

    composeTestRule.onNodeWithTag("add-entry").performClick()
    waitForEditScreen()

    // 若状态被复用，这里会显示上一笔的 50.00
    composeTestRule.onNodeWithText("0.00").assertIsDisplayed()
    composeTestRule.onNodeWithTag("save-button").assertIsDisplayed()
  }

  @Test
  fun adding_three_entries_in_a_row_keeps_working() {
    // 反复进入同一路由是最容易被状态复用坑到的场景，多跑一轮更保险
    addEntry(listOf("1"))
    addEntry(listOf("2"))
    addEntry(listOf("3"))

    composeTestRule.onAllNodesWithText("-1.00").assertCountEquals(1)
    composeTestRule.onAllNodesWithText("-2.00").assertCountEquals(1)
    composeTestRule.onAllNodesWithText("-3.00").assertCountEquals(1)
  }

  @Test
  fun bottom_tabs_switch_between_the_four_screens() {
    composeTestRule.onNodeWithTag("tab-budget").performClick()
    composeTestRule.onNodeWithText("月度总预算").assertIsDisplayed()

    composeTestRule.onNodeWithTag("tab-statistics").performClick()
    composeTestRule.onNodeWithText("该月没有支出记录").assertIsDisplayed()

    composeTestRule.onNodeWithTag("tab-settings").performClick()
    composeTestRule.onNodeWithText("导出 CSV").assertIsDisplayed()

    composeTestRule.onNodeWithTag("tab-ledger").performClick()
    composeTestRule.onNodeWithText("记账本").assertIsDisplayed()
  }

  @Test
  fun bottom_bar_is_hidden_on_the_edit_screen() {
    composeTestRule.onNodeWithTag("add-entry").performClick()
    waitForEditScreen()

    // 编辑页是覆盖在页签之上的全屏页面，不应显示底部页签
    composeTestRule.onNodeWithTag("tab-ledger").assertDoesNotExist()
    composeTestRule.onNodeWithTag("tab-budget").assertDoesNotExist()
  }

  @Test
  fun category_management_is_reachable_from_settings_and_can_be_left() {
    composeTestRule.onNodeWithTag("tab-settings").performClick()
    // 「分类管理」是设置页最后一张卡片，在视口之外——performClick 不会自动滚动，
    // 不先滚过去点击就会落空（这个坑实测踩到过）
    composeTestRule.onNodeWithTag("manage-categories").performScrollTo().performClick()

    composeTestRule.waitUntil(5_000) {
      composeTestRule.onAllNodesWithText("支出分类").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithText("分类管理").assertIsDisplayed()
    // 底栏在二级页面同样隐藏
    composeTestRule.onNodeWithTag("tab-settings").assertDoesNotExist()

    composeTestRule.onNodeWithContentDescription("返回").performClick()
    composeTestRule.onNodeWithText("导出 CSV").assertIsDisplayed()
  }

  @Test
  fun newly_created_category_can_be_used_right_away() {
    // 端到端：新建分类 → 回到记账页 → 该分类出现在可选列表里
    composeTestRule.onNodeWithTag("tab-settings").performClick()
    composeTestRule.onNodeWithTag("manage-categories").performScrollTo().performClick()
    composeTestRule.waitUntil(5_000) {
      composeTestRule.onAllNodesWithTag("add-category").fetchSemanticsNodes().isNotEmpty()
    }

    composeTestRule.onNodeWithTag("add-category").performClick()
    composeTestRule.onNodeWithTag("category-name").performTextInput("宠物")
    composeTestRule.onNodeWithTag("icon-pet").performClick()
    composeTestRule.onNodeWithTag("category-save").performClick()
    composeTestRule.waitUntil(5_000) {
      composeTestRule.onAllNodesWithText("宠物").fetchSemanticsNodes().isNotEmpty()
    }

    composeTestRule.onNodeWithContentDescription("返回").performClick()
    composeTestRule.onNodeWithTag("tab-ledger").performClick()
    composeTestRule.onNodeWithTag("add-entry").performClick()
    waitForEditScreen()

    composeTestRule.onNodeWithText("宠物").assertIsDisplayed()
  }
}
