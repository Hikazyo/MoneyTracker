package com.moneytracker.app.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.theme.MoneyTrackerTheme
import com.moneytracker.app.theme.expenseColor
import com.moneytracker.app.theme.incomeColor
import com.moneytracker.app.ui.components.CategoryVisuals
import com.moneytracker.app.ui.rememberLedgerRepository

@Composable
fun EditEntryScreen(
  entryId: Long?,
  onDone: () -> Unit,
  modifier: Modifier = Modifier,
  repository: LedgerRepository = rememberLedgerRepository(),
) {
  val scope = rememberCoroutineScope()
  // 用 remember 而不是 viewModel：Navigation3 1.0.1 没有导航项级的 ViewModelStoreOwner，
  // viewModel() 会落到 Activity 作用域，同一个 key 在多次导航间复用同一实例，
  // 导致上一次的输入和 finished 标记残留（记完一笔再进来会立刻自己弹回）。
  val holder = remember(entryId) { EditEntryStateHolder(repository, entryId, scope) }
  val state by holder.state.collectAsStateWithLifecycle()

  // 保存或删除完成后自动返回列表页
  LaunchedEffect(state.finished) {
    if (state.finished) onDone()
  }

  EditEntryContent(
    state = state,
    onBack = onDone,
    onDigit = holder::onDigit,
    onDot = holder::onDot,
    onBackspace = holder::onBackspace,
    onClear = holder::onClear,
    onTypeChange = holder::onTypeChange,
    onCategorySelect = holder::onCategorySelect,
    onNoteChange = holder::onNoteChange,
    onDateSelected = holder::onDateSelected,
    onSave = holder::save,
    onDelete = holder::delete,
    modifier = modifier,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditEntryContent(
  state: EditEntryUiState,
  onBack: () -> Unit,
  onDigit: (Char) -> Unit,
  onDot: () -> Unit,
  onBackspace: () -> Unit,
  onClear: () -> Unit,
  onTypeChange: (Boolean) -> Unit,
  onCategorySelect: (Long) -> Unit,
  onNoteChange: (String) -> Unit,
  onDateSelected: (Long) -> Unit,
  onSave: () -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showDatePicker by remember { mutableStateOf(false) }

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(state.title) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
        },
        actions = {
          if (state.isEditing) {
            IconButton(onClick = onDelete, modifier = Modifier.testTag("delete-button")) {
              Icon(Icons.Default.Delete, contentDescription = "删除")
            }
          }
        },
      )
    },
  ) { innerPadding ->
    Column(Modifier.padding(innerPadding).fillMaxSize()) {
      TypeToggle(isExpense = state.isExpense, onTypeChange = onTypeChange)
      AmountDisplay(amount = state.amountDisplay, isExpense = state.isExpense)

      // 中间区域可滚动，键盘与保存按钮固定在底部
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        CategoryGrid(categories = state.categories, onSelect = onCategorySelect)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        DateRow(dateLabel = state.dateLabel, onClick = { showDatePicker = true })
        NoteField(note = state.note, onNoteChange = onNoteChange)
      }

      state.errorMessage?.let { message ->
        Text(
          text = message,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
      }

      AmountKeypad(
        onDigit = onDigit,
        onDot = onDot,
        onBackspace = onBackspace,
        onClear = onClear,
        canSave = state.canSave,
        onSave = onSave,
      )
    }
  }

  if (showDatePicker) {
    val datePickerState = rememberDatePickerState()
    DatePickerDialog(
      onDismissRequest = { showDatePicker = false },
      confirmButton = {
        TextButton(
          onClick = {
            datePickerState.selectedDateMillis?.let(onDateSelected)
            showDatePicker = false
          }
        ) {
          Text("确定")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDatePicker = false }) { Text("取消") }
      },
    ) {
      DatePicker(state = datePickerState)
    }
  }
}

@Composable
private fun TypeToggle(isExpense: Boolean, onTypeChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FilterChip(
      selected = isExpense,
      onClick = { onTypeChange(true) },
      label = { Text("支出") },
      modifier = Modifier.weight(1f).testTag("type-expense"),
    )
    FilterChip(
      selected = !isExpense,
      onClick = { onTypeChange(false) },
      label = { Text("收入") },
      modifier = Modifier.weight(1f).testTag("type-income"),
    )
  }
}

@Composable
private fun AmountDisplay(amount: String, isExpense: Boolean) {
  Text(
    text = amount,
    style = MaterialTheme.typography.displaySmall,
    color = if (isExpense) expenseColor() else incomeColor(),
    fontWeight = FontWeight.SemiBold,
    textAlign = TextAlign.End,
    maxLines = 1,
    modifier =
      Modifier.fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 8.dp)
        .testTag("amount-display"),
  )
}

/**
 * 分类网格。
 *
 * 手写 Row 分列而不用 LazyVerticalGrid：分类总共十几个，不需要懒加载，
 * 而 LazyVerticalGrid 嵌在 verticalScroll 的同向滚动容器里容易触发布局约束问题。
 */
@Composable
private fun CategoryGrid(categories: List<CategoryChip>, onSelect: (Long) -> Unit) {
  val columns = 5
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
    categories.chunked(columns).forEach { rowItems ->
      Row(Modifier.fillMaxWidth()) {
        rowItems.forEach { chip ->
          CategoryCell(chip = chip, onSelect = onSelect, modifier = Modifier.weight(1f))
        }
        // 补齐空位，避免最后一行被拉伸变形
        repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
}

@Composable
private fun CategoryCell(chip: CategoryChip, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier =
      modifier
        .clickable { onSelect(chip.id) }
        .padding(vertical = 8.dp)
        .testTag("category-${chip.id}"),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier =
        Modifier.size(48.dp)
          .clip(CircleShape)
          .background(
            if (chip.isSelected) MaterialTheme.colorScheme.primary
            else CategoryVisuals.bubbleColor(chip.id)
          ),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = CategoryVisuals.emojiFor(chip.iconKey, chip.name),
        style = MaterialTheme.typography.titleLarge,
      )
    }
    Spacer(Modifier.height(4.dp))
    Text(
      text = chip.name,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      fontWeight = if (chip.isSelected) FontWeight.SemiBold else FontWeight.Normal,
    )
  }
}

@Composable
private fun DateRow(dateLabel: String, onClick: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp)
        .testTag("date-row"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("日期", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.weight(1f))
    Text(
      text = dateLabel,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.primary,
    )
  }
}

@Composable
private fun NoteField(note: String, onNoteChange: (String) -> Unit) {
  OutlinedTextField(
    value = note,
    onValueChange = onNoteChange,
    label = { Text("备注") },
    singleLine = true,
    modifier =
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("note-field"),
  )
}

@Composable
private fun AmountKeypad(
  onDigit: (Char) -> Unit,
  onDot: () -> Unit,
  onBackspace: () -> Unit,
  onClear: () -> Unit,
  canSave: Boolean,
  onSave: () -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(8.dp)) {
    listOf(
        listOf("1", "2", "3", "⌫"),
        listOf("4", "5", "6", "."),
        listOf("7", "8", "9", "清空"),
      )
      .forEach { rowKeys ->
        Row(Modifier.fillMaxWidth()) {
          rowKeys.forEach { key ->
            KeypadKey(
              label = key,
              modifier = Modifier.weight(1f).testTag("key-$key"),
              onClick = {
                when (key) {
                  "⌫" -> onBackspace()
                  "." -> onDot()
                  "清空" -> onClear()
                  else -> onDigit(key[0])
                }
              },
            )
          }
        }
      }
    Row(Modifier.fillMaxWidth()) {
      KeypadKey(
        label = "0",
        modifier = Modifier.weight(2f).testTag("key-0"),
        onClick = { onDigit('0') },
      )
      Spacer(Modifier.width(8.dp))
      Button(
        onClick = onSave,
        enabled = canSave,
        modifier = Modifier.weight(2f).height(56.dp).testTag("save-button"),
      ) {
        Text("保存")
      }
    }
  }
}

@Composable
private fun KeypadKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
  OutlinedButton(
    onClick = onClick,
    modifier = modifier.height(56.dp).padding(2.dp),
    contentPadding = PaddingValues(0.dp),
  ) {
    Text(label, style = MaterialTheme.typography.titleMedium)
  }
}

// ---------- 预览 ----------

private val PREVIEW_CHIPS =
  listOf(
    CategoryChip(1, "餐饮", "food", true),
    CategoryChip(2, "交通", "transport", false),
    CategoryChip(3, "购物", "shopping", false),
    CategoryChip(4, "居住", "housing", false),
    CategoryChip(5, "娱乐", "entertainment", false),
    CategoryChip(6, "医疗", "medical", false),
    CategoryChip(7, "学习", "education", false),
  )

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun EditEntryContentPreview() {
  MoneyTrackerTheme {
    EditEntryContent(
      state =
        EditEntryUiState(
          isEditing = false,
          isExpense = true,
          amountDisplay = "32.50",
          note = "午饭",
          dateLabel = "2026年10月10日",
          categories = PREVIEW_CHIPS,
          canSave = true,
        ),
      onBack = {},
      onDigit = {},
      onDot = {},
      onBackspace = {},
      onClear = {},
      onTypeChange = {},
      onCategorySelect = {},
      onNoteChange = {},
      onDateSelected = {},
      onSave = {},
      onDelete = {},
    )
  }
}
