package com.moneytracker.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneytracker.app.theme.MoneyTrackerTheme

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 账本首屏就是当月收支与备注，不该出现在「最近任务」缩略图、截图或录屏里。
    // FLAG_SECURE 是**窗口级**的，而对话框是独立窗口——它们是否也被盖住需要实测，
    // 见 DEV_NOTES 里关于该标志与 Compose Dialog 关系的记录。
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

    enableEdgeToEdge()

    // 提前触发一次建库。这里做的是「口令解析 + 明文库迁移 + 构造 Room 实例」，
    // 都不真正打开数据库文件，代价很小；换来的是：数据库读不出来时
    // 能在进入界面之前给出准确说明，而不是让用户对着崩溃或空白页发呆。
    val startupError = probeDatabase()

    setContent {
      MoneyTrackerTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          if (startupError == null) MainNavigation() else DatabaseErrorScreen(startupError)
        }
      }
    }
  }

  /** 返回 null 表示一切正常；否则返回给用户看的说明。 */
  private fun probeDatabase(): String? =
    try {
      (application as MoneyTrackerApp).container.ledgerRepository
      null
    } catch (e: Throwable) {
      e.message ?: e.javaClass.simpleName
    }
}

/**
 * 数据库读不出来时的界面。
 *
 * 刻意**不**做成「自动新建一个空账本」：那会让用户以为数据被自己弄丢了。
 * 事实是数据很可能还在这台设备上，只是解密口令失效了，
 * 所以这里要讲清楚发生了什么、以及还有哪条路可以走。
 */
@Composable
private fun DatabaseErrorScreen(detail: String) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      "无法打开账本",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(12.dp))
    Text(
      "本机的账本数据是加密保存的，而用于解密的密钥已经失效，因此现在读不出内容。",
      style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    Text(
      "请注意：这不代表数据被删除了。数据文件通常还在这台设备上，只是打不开。" +
        "应用不会自动清空它——那样你才会真的失去它。",
      style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    Text("可以尝试：", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text(
      "1. 如果你导出过备份文件（JSON），重新安装本应用后用「从备份恢复」导入，账目就能回来。\n" +
        "2. 如果这台设备有系统级整机备份且曾包含本应用数据，可尝试从系统恢复。\n" +
        "3. 以上都没有时，数据无法找回。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    Text(
      "技术细节：$detail",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
