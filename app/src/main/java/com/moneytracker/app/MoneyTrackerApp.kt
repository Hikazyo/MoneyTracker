package com.moneytracker.app

import android.app.Application
import android.content.Context
import com.moneytracker.app.data.db.MoneyTrackerDatabase
import com.moneytracker.app.data.repository.DefaultLedgerRepository
import com.moneytracker.app.data.repository.LedgerRepository

/**
 * 依赖容器。
 *
 * 第一版用手动 DI 而不引入 Hilt：本项目只有 1 个数据库、1 个仓库，
 * 手动装配足够清晰，还能省掉一整套注解处理器带来的编译时间与排查成本。
 */
interface AppContainer {
  val ledgerRepository: LedgerRepository
}

class DefaultAppContainer(context: Context) : AppContainer {
  // 懒初始化：避免冷启动时就付出建库开销
  private val database by lazy { MoneyTrackerDatabase.build(context) }

  override val ledgerRepository: LedgerRepository by lazy { DefaultLedgerRepository(database) }
}

class MoneyTrackerApp : Application() {
  val container: AppContainer by lazy { DefaultAppContainer(this) }
}
