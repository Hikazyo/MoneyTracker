package com.moneytracker.app.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 把 `Dispatchers.Main` 换成测试调度器。
 *
 * 必要性：ViewModel 的 `init` 会在 `viewModelScope` 里启动协程，而 viewModelScope 绑定
 * `Dispatchers.Main`。JVM 单元测试没有 Android 主线程 Looper，不替换就会在构造
 * ViewModel 时直接失败。
 *
 * 用 Unconfined 而非 Standard：让 init 里对 StateFlow 的首次收集同步执行完，
 * 断言时状态已经就绪，不必手动推进调度器。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) :
  TestWatcher() {

  override fun starting(description: Description) {
    Dispatchers.setMain(dispatcher)
  }

  override fun finished(description: Description) {
    Dispatchers.resetMain()
  }
}
