package com.moneytracker.app.ui.importer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.ui.components.CategoryVisuals

/**
 * 导入预览。
 *
 * 导入是批量写操作，用户必须先看清「会进来多少、多少条没能自动分类」，
 * 因此这里不做静默导入。
 */
@Composable
fun ImportPreviewDialog(
  preview: ImportPreview,
  fallbackExpenseCategoryId: Long?,
  fallbackIncomeCategoryId: Long?,
  canConfirm: Boolean,
  onSelectFallback: (TransactionType, Long) -> Unit,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("导入预览") },
    text = {
      Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        Text("识别为：${preview.formatLabel}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
          text = "可导入 ${preview.totalCount} 条" +
            if (preview.skippedRows > 0) "，跳过 ${preview.skippedRows} 条（不计收支或已关闭）" else "",
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          text = "其中 ${preview.autoMatchedCount} 条已自动匹配分类",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (preview.unmatchedExpenseCount > 0) {
          FallbackPicker(
            label = "未匹配的 ${preview.unmatchedExpenseCount} 条支出归入：",
            options = preview.expenseOptions,
            selectedId = fallbackExpenseCategoryId,
            type = TransactionType.EXPENSE,
            onSelect = onSelectFallback,
          )
        }

        if (preview.unmatchedIncomeCount > 0) {
          FallbackPicker(
            label = "未匹配的 ${preview.unmatchedIncomeCount} 条收入归入：",
            options = preview.incomeOptions,
            selectedId = fallbackIncomeCategoryId,
            type = TransactionType.INCOME,
            onSelect = onSelectFallback,
          )
        }

        if (!canConfirm) {
          Spacer(Modifier.height(8.dp))
          Text(
            text = "还有未匹配的账目没有可选分类，请先新建分类。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onConfirm,
        enabled = canConfirm,
        modifier = Modifier.testTag("import-confirm"),
      ) {
        Text("确认导入")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("import-cancel")) {
        Text("取消")
      }
    },
    modifier = Modifier.testTag("import-dialog"),
  )
}

@Composable
private fun FallbackPicker(
  label: String,
  options: List<ImportCategoryOption>,
  selectedId: Long?,
  type: TransactionType,
  onSelect: (TransactionType, Long) -> Unit,
) {
  Spacer(Modifier.height(12.dp))
  Text(label, style = MaterialTheme.typography.bodySmall)

  if (options.isEmpty()) {
    Text(
      text = "（没有可用分类）",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
    )
    return
  }

  // 每行 3 个：手写分列而不是 FlowRow，避免依赖实验性布局 API
  options.chunked(3).forEach { rowOptions ->
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      rowOptions.forEach { option ->
        FilterChip(
          selected = option.id == selectedId,
          onClick = { onSelect(type, option.id) },
          label = { Text(option.name) },
          leadingIcon = {
            Box(
              modifier =
                Modifier.size(20.dp)
                  .clip(CircleShape)
                  .background(CategoryVisuals.bubbleColor(option.id)),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = CategoryVisuals.emojiFor(option.iconKey, option.name),
                style = MaterialTheme.typography.labelSmall,
              )
            }
          },
          modifier = Modifier.testTag("import-fallback-${option.id}"),
        )
      }
      // 补齐空位，避免最后一行被拉伸
      repeat(3 - rowOptions.size) { Spacer(Modifier.weight(1f)) }
    }
  }
}

/** 导入按钮所在的卡片文案，单独抽出来便于测试断言。 */
@Composable
fun ImportHintText(modifier: Modifier = Modifier) {
  Text(
    text = "支持微信支付账单与支付宝账单导出的 CSV 文件。支付宝为 GBK 编码，已自动识别。",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontWeight = FontWeight.Normal,
    modifier = modifier,
  )
}
