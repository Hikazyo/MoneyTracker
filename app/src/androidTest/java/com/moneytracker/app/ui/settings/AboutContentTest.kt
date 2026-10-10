package com.moneytracker.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneytracker.app.theme.MoneyTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「关于与隐私」页测试。
 *
 * 断言的是**几件用户真正关心的事有没有讲清楚**，而不是逐字比对文案：
 * 数据存在哪、有没有联网、申请了哪些权限、怎么删掉。
 */
@RunWith(AndroidJUnit4::class)
class AboutContentTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun setContent(versionName: String = "1.0", onBack: () -> Unit = {}) {
    composeTestRule.setContent {
      MoneyTrackerTheme { AboutContent(versionName = versionName, onBack = onBack) }
    }
  }

  /**
   * 正文有六张卡片，首屏放不下。先滚动到目标再断言「可见」，
   * 这样断言的是「用户真的读得到」，顺便也验证了页面可滚动。
   */
  private fun assertBodyText(text: String) {
    composeTestRule.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()
  }

  @Test
  fun screen_title_and_version_are_shown() {
    setContent(versionName = "1.2.3")
    composeTestRule.onNodeWithText("关于与隐私").assertIsDisplayed()
    composeTestRule.onNodeWithText("1.2.3", substring = true).assertIsDisplayed()
  }

  @Test
  fun all_privacy_sections_are_present() {
    setContent()
    assertBodyText("你的数据在哪里")
    assertBodyText("收集哪些信息")
    assertBodyText("权限说明")
    assertBodyText("如何删除数据")
    assertBodyText("账单导入说明")
  }

  @Test
  fun states_that_data_stays_on_device() {
    setContent()
    assertBodyText("不会上传到任何服务器")
  }

  @Test
  fun states_that_the_app_has_no_network_permission() {
    setContent()
    assertBodyText("没有网络访问权限")
  }

  @Test
  fun states_that_no_personal_data_is_collected() {
    setContent()
    assertBodyText("不收集任何个人信息")
    assertBodyText("不含任何统计、埋点或第三方 SDK")
  }

  @Test
  fun explains_that_no_storage_permission_is_requested() {
    setContent()
    assertBodyText("不申请存储权限")
  }

  @Test
  fun explains_how_to_delete_data() {
    setContent()
    assertBodyText("清除数据")
    assertBodyText("卸载应用")
  }

  @Test
  fun explains_that_imported_bills_are_parsed_locally() {
    setContent()
    assertBodyText("只在本机解析")
  }

  @Test
  fun back_is_clickable() {
    var backs = 0
    setContent(onBack = { backs++ })
    composeTestRule.onNodeWithContentDescription("返回").performClick()
    assertEquals(1, backs)
  }
}
