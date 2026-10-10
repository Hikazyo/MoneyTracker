package com.moneytracker.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.moneytracker.app.MoneyTrackerApp
import com.moneytracker.app.data.repository.LedgerRepository

/**
 * 从 Application 容器取仓库——手动 DI 的对接点。
 *
 * 不用 Hilt 的代价就是需要这样一个函数，好处是依赖关系一眼可见、构建也更快。
 */
@Composable
fun rememberLedgerRepository(): LedgerRepository {
  val context = LocalContext.current
  return remember(context) {
    val app = context.applicationContext
    check(app is MoneyTrackerApp) {
      "Application 必须是 MoneyTrackerApp，实际为 ${app::class.java.name}"
    }
    app.container.ledgerRepository
  }
}
