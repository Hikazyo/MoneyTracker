package com.moneytracker.app.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.moneytracker.app.theme.MoneyTrackerTheme

/**
 * 关于与隐私政策。
 *
 * 内容不是走过场：上架应用商店要求提供隐私政策，而「记账」这类应用涉及财务数据，
 * 用户真正关心的是**数据存在哪、有没有联网、怎么删掉**。因此这里逐条讲清楚，
 * 并且每一条都与实现一致（本地 Room、无网络权限、导出走 SAF）。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val versionName = remember(context) { readVersionName(context) }

  AboutContent(versionName = versionName, onBack = onBack, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutContent(
  versionName: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text("关于与隐私") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
        },
      )
    },
  ) { innerPadding ->
    Column(
      Modifier.padding(innerPadding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .testTag("about-content")
    ) {
      InfoCard(title = "版本") {
        LabeledRow("应用版本", versionName)
        LabeledRow("包名", "com.moneytracker.app")
      }

      InfoCard(title = "你的数据在哪里") {
        Paragraph(
          "全部账目、分类与预算都只保存在这台设备的本地数据库中，不会上传到任何服务器。" +
            "本应用没有网络访问权限，因此从技术上也不具备上传数据的能力。"
        )
        Paragraph(
          "导出的 CSV 与备份文件由你自己选择保存位置（系统文件选择器），" +
            "保存在哪里、是否分享给他人完全由你决定。"
        )
      }

      InfoCard(title = "数据是怎么保护的") {
        Paragraph(
          "本机数据库以加密方式保存（SQLCipher / AES-256）。解密口令由系统密钥库" +
            "（Android Keystore）保管，不会以明文形式写在任何文件里。"
        )
        Paragraph(
          "因此单把数据库文件拷走（取证镜像、未加密的整机备份等）只能得到密文。" +
            "但若设备已解锁、且攻击者能在本应用的进程里执行代码（例如设备已 root），" +
            "他仍可以请求系统密钥库解密——加密挡不住这种情况。"
        )
        Paragraph(
          "账本不参与系统的云备份与换机迁移。换机请用「创建备份」导出的文件。" +
            "备份文件本身是可读的，建议给它设置口令——口令不会被保存，" +
            "忘记了那份备份就打不开。"
        )
      }

      InfoCard(title = "收集哪些信息") {
        Paragraph("不收集任何个人信息，不含任何统计、埋点或第三方 SDK。")
        Paragraph("应用不需要注册或登录，因此也不存在账号相关的个人信息。")
      }

      InfoCard(title = "权限说明") {
        Paragraph(
          "本应用不申请存储权限。导出账单与备份文件时通过系统提供的文件选择器完成，" +
            "你选中的那一个文件才会被读写。"
        )
        Paragraph("没有网络权限，没有位置、相机、通讯录、短信等权限。")
      }

      InfoCard(title = "如何删除数据") {
        Paragraph("在应用内逐笔删除账目，或在系统设置中「清除数据」一次性清空。")
        Paragraph("卸载应用会连同本地数据库一起删除，且无法恢复；如需保留请先导出备份。")
      }

      InfoCard(title = "账单导入说明") {
        Paragraph(
          "导入微信 / 支付宝账单时，文件只在本机解析，不会外传。" +
            "解析出的账目写入本地数据库，并记录来源单号用于避免重复导入。"
        )
      }

      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
  Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(8.dp))
      HorizontalDivider()
      Spacer(Modifier.height(8.dp))
      content()
    }
  }
}

@Composable
private fun Paragraph(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = 8.dp),
  )
}

@Composable
private fun LabeledRow(label: String, value: String) {
  Text(
    text = "$label：$value",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = 4.dp),
  )
}

/** 读版本号。用 PackageManager 而不是 BuildConfig——本项目关掉了 buildConfig 生成。 */
private fun readVersionName(context: Context): String =
  runCatching {
      context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }
    .getOrNull() ?: "未知"

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun AboutContentPreview() {
  MoneyTrackerTheme { AboutContent(versionName = "1.0", onBack = {}) }
}
