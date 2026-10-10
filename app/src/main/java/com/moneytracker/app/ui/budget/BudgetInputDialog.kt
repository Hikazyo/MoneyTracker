package com.moneytracker.app.ui.budget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import com.moneytracker.app.data.model.Money

/**
 * 预算金额输入框。
 *
 * 用系统数字键盘而不是记账页那套自定义键盘：这是设置类输入，不像记一笔那样追求
 * 单手快速录入，没必要为此再做一套键盘。
 *
 * 注意 [initialText] 必须是 [Money.format] 的结果（不带千分位）——
 * 带逗号的文案 [Money.parse] 解析不了。
 */
@Composable
fun BudgetInputDialog(
  title: String,
  initialText: String,
  allowClear: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (Long) -> Unit,
  onClear: () -> Unit,
) {
  var text by remember { mutableStateOf(initialText) }
  var error by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        OutlinedTextField(
          value = text,
          onValueChange = {
            text = it
            error = null
          },
          label = { Text("金额（元）") },
          singleLine = true,
          isError = error != null,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          supportingText = { error?.let { Text(it) } },
          modifier = Modifier.fillMaxWidth().testTag("budget-input"),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val minor = Money.parse(text)
          if (minor == null || minor <= 0L) {
            error = "请输入大于 0 的金额"
          } else {
            onConfirm(minor)
          }
        },
        modifier = Modifier.testTag("budget-confirm"),
      ) {
        Text("保存")
      }
    },
    dismissButton = {
      Row {
        if (allowClear) {
          TextButton(onClick = onClear, modifier = Modifier.testTag("budget-clear")) {
            Text("清除")
          }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("budget-cancel")) {
          Text("取消")
        }
      }
    },
  )
}
