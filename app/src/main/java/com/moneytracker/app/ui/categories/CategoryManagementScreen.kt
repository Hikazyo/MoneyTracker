package com.moneytracker.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.theme.MoneyTrackerTheme
import com.moneytracker.app.ui.components.CategoryVisuals
import com.moneytracker.app.ui.rememberLedgerRepository

@Composable
fun CategoryManagementScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  repository: LedgerRepository = rememberLedgerRepository(),
) {
  val viewModel: CategoryManagementViewModel = viewModel { CategoryManagementViewModel(repository) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  CategoryManagementContent(
    state = state,
    onBack = onBack,
    onAdd = viewModel::addCategory,
    onEdit = viewModel::editCategory,
    onDelete = viewModel::deleteCategory,
    onArchive = viewModel::archiveCategory,
    onUnarchive = viewModel::unarchiveCategory,
    onMessageShown = viewModel::onMessageShown,
    modifier = modifier,
  )
}

/** 对话框的目标。 */
private sealed interface EditTarget {
  data class New(val type: TransactionType) : EditTarget

  data class Existing(val row: CategoryRow) : EditTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryManagementContent(
  state: CategoryManagementUiState,
  onBack: () -> Unit,
  onAdd: (String, String, TransactionType) -> Unit,
  onEdit: (Long, String, String, TransactionType) -> Unit,
  onDelete: (CategoryRow) -> Unit,
  onArchive: (Long) -> Unit,
  onUnarchive: (Long) -> Unit,
  onMessageShown: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  var editTarget by remember { mutableStateOf<EditTarget?>(null) }
  var archiveTarget by remember { mutableStateOf<CategoryRow?>(null) }
  var deleteTarget by remember { mutableStateOf<CategoryRow?>(null) }

  LaunchedEffect(state.message) {
    state.message?.let {
      snackbarHostState.showSnackbar(it)
      onMessageShown()
    }
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text("分类管理") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
        },
      )
    },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = { editTarget = EditTarget.New(TransactionType.EXPENSE) },
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text("新建分类") },
        modifier = Modifier.testTag("add-category"),
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    Column(
      Modifier.padding(innerPadding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .testTag("category-management")
    ) {
      when {
        state.errorMessage != null -> Message("加载失败：${state.errorMessage}")
        state.isLoading -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
          CircularProgressIndicator()
        }
        else -> {
          CategorySection(
            title = "支出分类",
            hint = "内置分类可以改名与换图标，但不能改方向或归档；自定义分类都可以",
            active = state.activeExpense,
            archived = state.archivedExpense,
            testTag = "expense",
            onEdit = { editTarget = EditTarget.Existing(it) },
            onDelete = { deleteTarget = it },
            onArchive = { archiveTarget = it },
            onUnarchive = { onUnarchive(it.id) },
          )
          CategorySection(
            title = "收入分类",
            hint = "内置分类可以改名与换图标，但不能改方向或归档；自定义分类都可以",
            active = state.activeIncome,
            archived = state.archivedIncome,
            testTag = "income",
            onEdit = { editTarget = EditTarget.Existing(it) },
            onDelete = { deleteTarget = it },
            onArchive = { archiveTarget = it },
            onUnarchive = { onUnarchive(it.id) },
          )
          Spacer(Modifier.height(96.dp))
        }
      }
    }
  }

  editTarget?.let { target ->
    when (target) {
      is EditTarget.New ->
        CategoryEditDialog(
          title = "新建分类",
          initialName = "",
          initialIconKey = "star",
          initialType = target.type,
          policy = CategoryEditPolicy.ForNew,
          onDismiss = { editTarget = null },
          onConfirm = { name, iconKey, type ->
            onAdd(name, iconKey, type)
            editTarget = null
          },
        )

      is EditTarget.Existing ->
        CategoryEditDialog(
          title = "编辑分类",
          initialName = target.row.name,
          initialIconKey = target.row.iconKey,
          initialType = target.row.type,
          policy = target.row.editPolicy,
          onDismiss = { editTarget = null },
          onConfirm = { name, iconKey, type ->
            onEdit(target.row.id, name, iconKey, type)
            editTarget = null
          },
        )
    }
  }

  deleteTarget?.let { row ->
    DeleteCategoryDialog(
      row = row,
      onDismiss = { deleteTarget = null },
      onConfirm = {
        onDelete(row)
        deleteTarget = null
      },
    )
  }

  archiveTarget?.let { row ->
    ArchiveCategoryDialog(
      row = row,
      onDismiss = { archiveTarget = null },
      onConfirm = {
        onArchive(row.id)
        archiveTarget = null
      },
    )
  }
}

@Composable
private fun CategorySection(
  title: String,
  hint: String,
  active: List<CategoryRow>,
  archived: List<CategoryRow>,
  testTag: String,
  onEdit: (CategoryRow) -> Unit,
  onDelete: (CategoryRow) -> Unit,
  onArchive: (CategoryRow) -> Unit,
  onUnarchive: (CategoryRow) -> Unit,
) {
  Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Column(Modifier.fillMaxWidth()) {
      SectionHeader(title, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
      Text(
        text = hint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
      )

      active.forEachIndexed { index, row ->
        CategoryRowItem(
          row = row,
          onEdit = { onEdit(row) },
          onDelete = { onDelete(row) },
          onArchive = { onArchive(row) },
          onUnarchive = { onUnarchive(row) },
        )
        if (index != active.lastIndex) HorizontalDivider(Modifier.padding(start = 68.dp))
      }

      if (archived.isNotEmpty()) {
        HorizontalDivider()
        Text(
          text = "已归档",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier =
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("archived-$testTag"),
        )
        archived.forEach { row ->
          CategoryRowItem(
            row = row,
            onEdit = { onEdit(row) },
            onDelete = { onDelete(row) },
            onArchive = { onArchive(row) },
            onUnarchive = { onUnarchive(row) },
          )
        }
      }
    }
  }
}

@Composable
private fun CategoryRowItem(
  row: CategoryRow,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onArchive: () -> Unit,
  onUnarchive: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
        .testTag("category-row-${row.id}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier.size(36.dp).clip(CircleShape).background(CategoryVisuals.bubbleColor(row.id)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = CategoryVisuals.emojiFor(row.iconKey, row.name),
        style = MaterialTheme.typography.bodyLarge,
      )
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(
        text = row.name,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = buildSubtitle(row),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    TextButton(onClick = onEdit, modifier = Modifier.testTag("edit-${row.id}")) {
      Text("编辑", style = MaterialTheme.typography.labelMedium)
    }

    if (row.isArchived) {
      TextButton(onClick = onUnarchive, modifier = Modifier.testTag("unarchive-${row.id}")) {
        Text("恢复", style = MaterialTheme.typography.labelMedium)
      }
    } else {
      if (row.canDelete) {
        TextButton(onClick = onDelete, modifier = Modifier.testTag("delete-${row.id}")) {
          Text(
            "删除",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
      if (row.canArchive) {
        TextButton(onClick = onArchive, modifier = Modifier.testTag("archive-${row.id}")) {
          Text("归档", style = MaterialTheme.typography.labelMedium)
        }
      }
    }
  }
}

private fun buildSubtitle(row: CategoryRow): String {
  val parts = buildList {
    if (row.isArchived) add("已归档")
    if (row.isBuiltIn) add("内置")
    add("${row.entryCount} 笔")
  }
  return parts.joinToString(" · ")
}

@Composable
private fun Message(text: String) {
  Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

// ---------- 预览 ----------

private val PREVIEW_STATE =
  CategoryManagementUiState(
    isLoading = false,
    expense =
      listOf(
        CategoryRow(1, "餐饮", "food", TransactionType.EXPENSE, isBuiltIn = true, isArchived = false, entryCount = 12),
        CategoryRow(2, "交通", "transport", TransactionType.EXPENSE, isBuiltIn = true, isArchived = false, entryCount = 0),
        CategoryRow(20, "宠物", "pet", TransactionType.EXPENSE, isBuiltIn = false, isArchived = false, entryCount = 3),
        CategoryRow(22, "咖啡", "coffee", TransactionType.EXPENSE, isBuiltIn = false, isArchived = false, entryCount = 0),
        CategoryRow(21, "健身", "sport", TransactionType.EXPENSE, isBuiltIn = false, isArchived = true, entryCount = 8),
      ),
    income = listOf(CategoryRow(11, "工资", "salary", TransactionType.INCOME, true, false, 3)),
  )

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun CategoryManagementPreview() {
  MoneyTrackerTheme {
    CategoryManagementContent(
      state = PREVIEW_STATE,
      onBack = {},
      onAdd = { _, _, _ -> },
      onEdit = { _, _, _, _ -> },
      onDelete = {},
      onArchive = {},
      onUnarchive = {},
      onMessageShown = {},
    )
  }
}
