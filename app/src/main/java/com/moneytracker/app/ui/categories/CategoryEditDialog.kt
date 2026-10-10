package com.moneytracker.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.ui.components.CategoryVisuals

/**
 * 新建 / 编辑分类。
 *
 * 能改哪些字段由 [CategoryEditPolicy] 决定，**不能改的项仍然显示出来并说明原因**，
 * 而不是直接隐藏。隐藏会让人以为功能没做；说明原因才让人理解为什么不能改，
 * 也才知道该怎么做（比如「已有 3 笔账目」→ 想改方向得先处理那些账目）。
 */
@Composable
fun CategoryEditDialog(
  title: String,
  initialName: String,
  initialIconKey: String,
  initialType: TransactionType,
  policy: CategoryEditPolicy,
  onDismiss: () -> Unit,
  onConfirm: (name: String, iconKey: String, type: TransactionType) -> Unit,
) {
  var name by remember { mutableStateOf(initialName) }
  var iconKey by remember { mutableStateOf(initialIconKey) }
  var type by remember { mutableStateOf(initialType) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("分类名") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("category-name"),
        )

        Spacer(Modifier.height(12.dp))
        if (policy.canEditType) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
              selected = type == TransactionType.EXPENSE,
              onClick = { type = TransactionType.EXPENSE },
              label = { Text("支出") },
              modifier = Modifier.weight(1f).testTag("category-type-expense"),
            )
            FilterChip(
              selected = type == TransactionType.INCOME,
              onClick = { type = TransactionType.INCOME },
              label = { Text("收入") },
              modifier = Modifier.weight(1f).testTag("category-type-income"),
            )
          }
        } else {
          FieldLabel("方向")
          Text(
            text = directionLabel(type),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("category-type-readonly"),
          )
          policy.typeLockedReason?.let { Hint(it) }
        }

        Spacer(Modifier.height(12.dp))
        FieldLabel("图标")
        if (policy.canEditIcon) {
          Spacer(Modifier.height(6.dp))
          IconPicker(selectedKey = iconKey, onSelect = { iconKey = it })
        } else {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier =
                Modifier.size(36.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.surfaceVariant),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = CategoryVisuals.emojiFor(iconKey, name),
                style = MaterialTheme.typography.titleMedium,
              )
            }
            Spacer(Modifier.width(12.dp))
            Text(
              text = policy.iconLockedReason.orEmpty(),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f).testTag("category-icon-readonly"),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onConfirm(name, iconKey, type) },
        modifier = Modifier.testTag("category-save"),
      ) {
        Text("保存")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("category-cancel")) {
        Text("取消")
      }
    },
    modifier = Modifier.testTag("category-dialog"),
  )
}

private fun directionLabel(type: TransactionType): String =
  if (type == TransactionType.EXPENSE) "支出" else "收入"

@Composable
private fun FieldLabel(text: String) {
  Text(text, style = MaterialTheme.typography.labelMedium)
}

/** 解释「为什么这一项不能改」。 */
@Composable
private fun Hint(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 2.dp),
  )
}

/** 图标选择网格。每行 6 个：手写分列而不是 LazyVerticalGrid，避免嵌套滚动的约束问题。 */
@Composable
private fun IconPicker(selectedKey: String, onSelect: (String) -> Unit) {
  val columns = 6
  Column(Modifier.fillMaxWidth()) {
    CategoryVisuals.iconChoices.chunked(columns).forEach { rowIcons ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        rowIcons.forEach { icon ->
          val selected = icon.key == selectedKey
          Box(
            modifier =
              Modifier.weight(1f)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(
                  if (selected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable { onSelect(icon.key) }
                .testTag("icon-${icon.key}"),
            contentAlignment = Alignment.Center,
          ) {
            Text(icon.emoji, style = MaterialTheme.typography.titleMedium)
          }
        }
        repeat(columns - rowIcons.size) { Spacer(Modifier.weight(1f)) }
      }
      Spacer(Modifier.height(6.dp))
    }
  }
}

/**
 * 归档确认。
 *
 * 明确写出「历史账目会保留」——用户最担心的就是归档会不会把过去的记录一起弄丢。
 */
@Composable
fun ArchiveCategoryDialog(
  row: CategoryRow,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("归档分类") },
    text = {
      Text(
        if (row.entryCount > 0) {
          "「${row.name}」下有 ${row.entryCount} 笔账目。归档后新记账不再可选该分类，" +
            "但已有的 ${row.entryCount} 笔记录会完整保留。"
        } else {
          "「${row.name}」下还没有账目。归档后新记账不再可选该分类，之后可以随时恢复。"
        }
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm, modifier = Modifier.testTag("archive-confirm")) {
        Text("归档")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("archive-cancel")) {
        Text("取消")
      }
    },
    modifier = Modifier.testTag("archive-dialog"),
  )
}

/** 区块标题。 */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.SemiBold,
    modifier = modifier,
  )
}

/**
 * 删除确认。
 *
 * 这是**唯一**提到删除的入口，而且只对名下没有账目的自定义分类开放（见 [CategoryRow.canDelete]）。
 * 因此文案的重点不是「归档更安全」，而是「不可恢复」——用户没有任何理由以为它还能撤销。
 */
@Composable
fun DeleteCategoryDialog(
  row: CategoryRow,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("删除分类") },
    text = {
      Text("「${row.name}」下还没有账目，可以安全删除。删除后无法恢复，需要时得重新创建。")
    },
    confirmButton = {
      TextButton(onClick = onConfirm, modifier = Modifier.testTag("delete-confirm")) {
        Text("删除", color = MaterialTheme.colorScheme.error)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("delete-cancel")) {
        Text("取消")
      }
    },
    modifier = Modifier.testTag("delete-dialog"),
  )
}
