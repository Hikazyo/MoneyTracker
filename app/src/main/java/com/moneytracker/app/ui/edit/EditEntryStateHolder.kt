package com.moneytracker.app.ui.edit

import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.repository.LedgerRepository
import com.moneytracker.app.ui.ledger.LedgerFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryChip(
  val id: Long,
  val name: String,
  val iconKey: String,
  val isSelected: Boolean,
  /**
   * 这个分类已被归档。
   *
   * 只可能出现在「编辑一笔原本就属于该归档分类的账目」这一种情况，
   * 界面据此加一句说明，让用户知道为什么列表里会有一个已归档的分类。
   */
  val isArchived: Boolean = false,
)

data class EditEntryUiState(
  val isEditing: Boolean = false,
  val isExpense: Boolean = true,
  val amountDisplay: String = "0.00",
  val note: String = "",
  val dateLabel: String = "",
  val categories: List<CategoryChip> = emptyList(),
  val canSave: Boolean = false,
  /** 保存或删除完成，界面据此返回上一页。 */
  val finished: Boolean = false,
  val errorMessage: String? = null,
) {
  val title: String get() = if (isEditing) "编辑账目" else "记一笔"
}

/**
 * 「记一笔 / 编辑账目」的状态机。
 *
 * **为什么是普通类而不是 ViewModel**：Navigation3 1.0.1 没有为每个导航项提供
 * ViewModelStoreOwner（构件里既没有相关装饰器，也没有任何 ViewModelStore 类），
 * 因此 `viewModel {}` 会落到 **Activity 作用域**——同一个 key 在多次导航之间会复用
 * 同一个实例，上一次的输入、选中分类和 finished 标记都会残留。
 * 表现就是：记完一笔再点「记一笔」，页面会立刻自己弹回去。
 *
 * 改为由界面用 `remember(entryId)` 创建、并把 composable 的 CoroutineScope 传进来，
 * 生命周期就与导航项一致：离开页面即销毁，下次进入是全新状态。
 *
 * 代价：不再具备 ViewModel 的配置变更（如旋转屏幕）存活能力。
 * 这是 Navigation3 1.0.1 的能力限制下的取舍，后续版本若补上
 * `rememberViewModelStoreNavEntryDecorator` 可改回 ViewModel。
 */
class EditEntryStateHolder(
  private val repository: LedgerRepository,
  private val entryId: Long? = null,
  private val scope: CoroutineScope,
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val clock: () -> Long = System::currentTimeMillis,
) {

  private var type: TransactionType = TransactionType.EXPENSE
  private var amount: String = ""
  private var note: String = ""
  private var occurredAt: Long = clock()
  private var selectedCategoryId: Long? = null
  private var allCategories: List<CategoryEntity> = emptyList()

  private val _state = MutableStateFlow(EditEntryUiState(isEditing = entryId != null))
  val state: StateFlow<EditEntryUiState> = _state.asStateFlow()

  init {
    scope.launch {
      if (entryId != null) {
        repository.findTransaction(entryId)?.let { existing ->
          type = existing.type
          amount = Money.format(existing.amountMinor)
          note = existing.note.orEmpty()
          occurredAt = existing.occurredAt
          selectedCategoryId = existing.categoryId
        }
      }
      // 分类表变化（用户新增/归档分类）也会自动反映到界面。
      // 用**含已归档**的那份：归档一个分类之后，属于它的历史账目在编辑时
      // 仍要能认出自己的分类，否则一保存就会被换成别的分类。
      repository.observeAllCategories().collect { categories ->
        allCategories = categories
        refresh()
      }
    }
  }

  fun onDigit(digit: Char) {
    amount = AmountInput.appendDigit(amount, digit)
    refresh()
  }

  fun onDot() {
    amount = AmountInput.appendDot(amount)
    refresh()
  }

  fun onBackspace() {
    amount = AmountInput.backspace(amount)
    refresh()
  }

  fun onClear() {
    amount = AmountInput.clear()
    refresh()
  }

  fun onTypeChange(isExpense: Boolean) {
    type = if (isExpense) TransactionType.EXPENSE else TransactionType.INCOME
    refresh()
  }

  fun onCategorySelect(categoryId: Long) {
    selectedCategoryId = categoryId
    refresh()
  }

  fun onNoteChange(value: String) {
    note = value
    refresh()
  }

  /**
   * 选择日期。
   *
   * 注意：Material3 的 DatePicker 返回的是**UTC 零点**的毫秒值，不能直接当本地时间用，
   * 否则东八区会整体偏移一天。这里先按 UTC 取出用户选的那个日期，
   * 再与原有的时分秒组合成本地时间。
   */
  fun onDateSelected(utcMillis: Long) {
    val pickedDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val timeOfDay = Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalTime()
    occurredAt = pickedDate.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
    refresh()
  }

  fun save() {
    val minor = Money.parse(amount)
    if (minor == null || minor <= 0L) {
      setError("请输入大于 0 的金额")
      return
    }
    val categoryId = selectedCategoryId
    if (categoryId == null) {
      setError("请选择分类")
      return
    }
    val cleanedNote = note.trim().takeIf { it.isNotEmpty() }

    scope.launch {
      try {
        if (entryId == null) {
          repository.addTransaction(minor, type, categoryId, cleanedNote, occurredAt)
        } else {
          repository.updateTransaction(entryId, minor, type, categoryId, cleanedNote, occurredAt)
        }
        _state.update { it.copy(finished = true) }
      } catch (e: Exception) {
        setError(e.message ?: "保存失败")
      }
    }
  }

  fun delete() {
    val id = entryId ?: return
    scope.launch {
      try {
        repository.deleteTransaction(id)
        _state.update { it.copy(finished = true) }
      } catch (e: Exception) {
        setError(e.message ?: "删除失败")
      }
    }
  }

  private fun setError(message: String) {
    _state.update { it.copy(errorMessage = message) }
  }

  /**
   * 依据当前字段重建界面状态。
   *
   * 每次输入后整体重建（而不是局部 copy），是为了让 canSave、分类高亮、
   * 金额显示这些互相依赖的派生字段始终一致，避免出现「按钮可点但保存失败」这类不同步问题。
   */
  private fun refresh() {
    // 注意这里用的是**含已归档**的分类表。若只用未归档的那份，下面这段逻辑
    // 就会在「账目所属分类已被归档」时把它当成非法值，静默换成第一个分类——
    // 用户只想改个备注，分类却被改掉了，历史统计随之失真。
    val sameDirection = allCategories.filter { it.type == type }

    // 切换收支方向后，原先选中的分类可能已不属于当前方向，需要重新挑选
    if (sameDirection.none { it.id == selectedCategoryId }) {
      selectedCategoryId = sameDirection.firstOrNull { !it.isArchived }?.id
    }

    // 已归档的分类**只在该笔账目原本就属于它时**才出现在候选里。
    // 这样既能原样保存、不改动分类，又不会把已归档的分类重新塞回可选列表。
    val visible = sameDirection.filter { !it.isArchived || it.id == selectedCategoryId }
    val selected = selectedCategoryId
    val minor = Money.parse(amount)

    _state.value =
      EditEntryUiState(
        isEditing = entryId != null,
        isExpense = type == TransactionType.EXPENSE,
        amountDisplay = AmountInput.display(amount),
        note = note,
        dateLabel =
          LedgerFormatter.dateLabel(Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalDate()),
        categories =
          visible.map {
            CategoryChip(
              id = it.id,
              name = it.name,
              iconKey = it.iconKey,
              isSelected = it.id == selected,
              isArchived = it.isArchived,
            )
          },
        canSave = minor != null && minor > 0L && selected != null,
        finished = _state.value.finished,
        errorMessage = null,
      )
  }
}
