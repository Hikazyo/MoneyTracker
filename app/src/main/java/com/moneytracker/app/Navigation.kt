package com.moneytracker.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.moneytracker.app.ui.budget.BudgetScreen
import com.moneytracker.app.ui.categories.CategoryManagementScreen
import com.moneytracker.app.ui.edit.EditEntryScreen
import com.moneytracker.app.ui.ledger.LedgerScreen
import com.moneytracker.app.ui.screenshots.ScreenshotImportScreen
import com.moneytracker.app.ui.settings.AboutScreen
import com.moneytracker.app.ui.settings.SettingsScreen
import com.moneytracker.app.ui.statistics.StatisticsScreen

/**
 * 顶层页签。
 *
 * 图标用 emoji 而不是 material-icons-core：那套图标里没有合适的「统计」「预算」图标，
 * 而 material-icons-extended 体积过大。这与分类图标采用 emoji 是同一个取舍。
 */
private data class Tab(val route: NavKey, val emoji: String, val label: String, val tag: String)

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(LedgerRoute)

  val tabs =
    listOf(
      Tab(LedgerRoute, "📋", "账单", "tab-ledger"),
      Tab(StatisticsRoute, "📊", "统计", "tab-statistics"),
      Tab(BudgetRoute, "💰", "预算", "tab-budget"),
      Tab(SettingsRoute, "⚙️", "设置", "tab-settings"),
    )

  val current = backStack.lastOrNull()
  val currentTab = tabs.firstOrNull { it.route == current }

  Scaffold(
    // 置零窗口内边距：各页签自带 Scaffold 与 TopAppBar，会自行处理系统栏；
    // 外层若再叠加一次，顶部会出现双份留白。
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    bottomBar = {
      // 编辑页不是页签页（它是覆盖在页签之上的全屏页面），此时不显示底栏
      if (currentTab != null) {
        NavigationBar {
          tabs.forEach { tab ->
            NavigationBarItem(
              selected = tab.route == currentTab.route,
              onClick = {
                if (tab.route != current) {
                  // 页签之间是平级切换：清空后压入目标页签，避免返回栈无限增长
                  backStack.clear()
                  backStack.add(tab.route)
                }
              },
              icon = { Text(tab.emoji) },
              label = { Text(tab.label) },
              // 页签文案与顶栏标题重名（如「统计」），测试里按文字查找会有歧义
              modifier = Modifier.testTag(tab.tag),
            )
          }
        }
      }
    },
  ) { innerPadding ->
    Box(Modifier.padding(innerPadding).fillMaxSize()) {
      NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider =
          entryProvider {
            entry<LedgerRoute> {
              LedgerScreen(
                onAddEntry = { backStack.add(EditEntryRoute()) },
                onEditEntry = { entryId -> backStack.add(EditEntryRoute(entryId)) },
              )
            }
            entry<StatisticsRoute> { StatisticsScreen() }
            entry<BudgetRoute> { BudgetScreen() }
            entry<SettingsRoute> {
              SettingsScreen(
                onOpenCategories = { backStack.add(CategoriesRoute) },
                onOpenAbout = { backStack.add(AboutRoute) },
                onOpenScreenshotImport = { backStack.add(ScreenshotImportRoute) },
              )
            }
            entry<CategoriesRoute> { CategoryManagementScreen(onBack = { backStack.removeLastOrNull() }) }
            entry<AboutRoute> { AboutScreen(onBack = { backStack.removeLastOrNull() }) }
            entry<ScreenshotImportRoute> {
              ScreenshotImportScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry<EditEntryRoute> { route ->
              EditEntryScreen(entryId = route.entryId, onDone = { backStack.removeLastOrNull() })
            }
          },
      )
    }
  }
}
