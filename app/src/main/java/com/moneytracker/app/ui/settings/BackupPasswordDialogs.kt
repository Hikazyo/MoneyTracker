package com.moneytracker.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 创建备份时设置口令（可以留空 = 明文保存）。
 *
 * 两个必须让用户看到的点：
 *
 *  1. **口令不会被保存在任何地方**，忘记就等于这份备份作废。这是加密的固有代价，
 *     不讲清楚的话，用户设了密码、过半年换机时打不开，会认为是应用的 bug。
 *  2. **留空会明文保存**。备份里是全部账目，落点又常常是 Download 或网盘同步目录，
 *     所以这里要把风险说出来，而不是默默按默认值走。
 *
 * 要求二次输入是为了防打字错误：备份是「当下不做、将来才用」的东西，
 * 输错一个字符当时毫无感觉，等真要用时才发现打不开。
 */
@Composable
fun CreateBackupPasswordDialog(
  onDismiss: () -> Unit,
  onConfirm: (password: String?) -> Unit,
) {
  var password by remember { mutableStateOf("") }
  var confirmation by remember { mutableStateOf("") }

  val usePlaintext = password.isEmpty()
  val mismatch = !usePlaintext && password != confirmation

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("备份口令") },
    text = {
      Column(Modifier.fillMaxWidth()) {
        Text(
          "给这份备份设置一个口令。以后恢复时需要输入它。",
          style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text("口令") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth().testTag("backup-password"),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = confirmation,
          onValueChange = { confirmation = it },
          label = { Text("再输一次") },
          singleLine = true,
          isError = mismatch,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth().testTag("backup-password-confirm"),
        )

        if (mismatch) {
          Spacer(Modifier.height(6.dp))
          Text(
            "两次输入不一致",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
          )
        }

        Spacer(Modifier.height(12.dp))
        if (usePlaintext) {
          Text(
            // 注意：Compose 的 Text 不解析 Markdown，写 **明文** 只会把星号原样显示出来。
            // 强调靠 color = error 已经足够。
            "未设置口令：备份将以明文保存。任何拿到该文件的人都能读到全部账目，" +
              "如果把它放在网盘同步目录里尤其要注意。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("backup-plaintext-warning"),
          )
        } else {
          Text(
            "口令不会被保存在任何地方。忘记口令则这份备份无法恢复。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("backup-password-hint"),
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onConfirm(password.ifEmpty { null }) },
        enabled = !mismatch,
        modifier = Modifier.testTag("backup-password-save"),
      ) {
        Text("继续")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("backup-password-cancel")) {
        Text("取消")
      }
    },
    modifier = Modifier.testTag("backup-password-dialog"),
  )
}

/**
 * 恢复加密备份时索取口令。
 *
 * 口令错误时**保留已输入的内容**并给出提示，而不是清空重来：
 * 清空会让用户以为是自己操作错了，而实际上只是打错了一个字符。
 */
@Composable
fun RestoreBackupPasswordDialog(
  wrongPassword: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (password: String) -> Unit,
) {
  var password by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("这份备份已加密") },
    text = {
      Column(Modifier.fillMaxWidth()) {
        Text("请输入创建该备份时设置的口令。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text("口令") },
          singleLine = true,
          isError = wrongPassword,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth().testTag("restore-password"),
        )
        if (wrongPassword) {
          Spacer(Modifier.height(6.dp))
          Text(
            "口令不正确，或文件已被修改",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("restore-password-error"),
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onConfirm(password) },
        enabled = password.isNotEmpty(),
        modifier = Modifier.testTag("restore-password-submit"),
      ) {
        Text("解密")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("restore-password-cancel")) {
        Text("取消")
      }
    },
    modifier = Modifier.testTag("restore-password-dialog"),
  )
}
