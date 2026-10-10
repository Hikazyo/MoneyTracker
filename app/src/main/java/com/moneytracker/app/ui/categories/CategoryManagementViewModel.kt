package com.moneytracker.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.data.repository.LedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 编辑一个已有分类时，哪些字段可以改。
 *
 * 为什么不把这些规则写成对话框里的 `if`：它们决定的是「会不会让历史数据变得自相矛盾」，
 * 属于业务约束而不是排版细节，必须能被单元测试覆盖，而不是只能靠手点界面验证。
 */
data class CategoryEditPolicy(
  val canEditIcon: Boolean,
  val canEditType: Boolean,
  /** 图标不可改时展示给用户的原因；可改时为 null。 */
  val iconLockedReason: String? = null,
  /** 方向不可改时的原因；可改时为 null。 */
  val typeLockedReason: String? = null,
) {
  companion object {

    /** 新建：什么都能选。 */
    val ForNew: CategoryEditPolicy =
      CategoryEditPolicy(canEditIcon = true, canEditType = true)

    fun forExisting(row: CategoryRow): CategoryEditPolicy =
      when {
        // 内置分类要挡住两件事，理由不同，所以分开说明
        row.isBuiltIn ->
          CategoryEditPolicy(
            canEditIcon = false,
            canEditType = false,
            iconLockedReason = "内置分类的图标是账单导入时自动归类的依据，改了会让那几类账目匹配不上分类",
            typeLockedReason = "内置分类的收支方向是固定的",
          )

        // 自定义分类：图标随便改（不影响任何已有数据），方向则要看名下有没有账目
        row.entryCount > 0 ->
          CategoryEditPolicy(
            canEditIcon = true,
            canEditType = false,
            typeLockedReason = "该分类下已有 ${row.entryCount} 笔账目，改方向会让收支统计对不上",
          )

        else -> CategoryEditPolicy(canEditIcon = true, canEditType = true)
      }
  }
}

/** 分类管理页的一行。 */
data class CategoryRow(
  val id: Long,
  val name: String,
  val iconKey: String,
  val type: TransactionType,
  val isBuiltIn: Boolean,
  val isArchived: Boolean,
  val entryCount: Int,
) {
  /**
   * 只有「自定义」且「名下还有账目」的分类才给「归档」。
   *
   * 名下一笔账都没有时，归档与删除的唯一区别是「能不能撤销」，而两者都只是让这个分类
   * 从记账选项里消失。此时并排给两个按钮，用户反而要停下来想「归档和删除差在哪」——
   * 直接给「删除」更干脆，也不会有归档列表越攒越长的问题。
   */
  val canArchive: Boolean get() = !isBuiltIn && entryCount > 0

  /**
   * 只有「自定义」且「名下没有账目」的分类才能真删。
   *
   * 有账目的情况一律走归档：删掉分类会让那些账目失去归属，
   * 而归档保留了引用，历史记录仍然完整。
   */
  val canDelete: Boolean get() = !isBuiltIn && entryCount == 0

  val editPolicy: CategoryEditPolicy get() = CategoryEditPolicy.forExisting(this)
}

data class CategoryManagementUiState(
  val isLoading: Boolean = true,
  val expense: List<CategoryRow> = emptyList(),
  val income: List<CategoryRow> = emptyList(),
  val message: String? = null,
  val errorMessage: String? = null,
) {
  val activeExpense: List<CategoryRow> get() = expense.filter { !it.isArchived }
  val archivedExpense: List<CategoryRow> get() = expense.filter { it.isArchived }
  val activeIncome: List<CategoryRow> get() = income.filter { !it.isArchived }
  val archivedIncome: List<CategoryRow> get() = income.filter { it.isArchived }

  fun rowsOf(type: TransactionType): List<CategoryRow> =
    if (type == TransactionType.EXPENSE) expense else income
}

class CategoryManagementViewModel(private val repository: LedgerRepository) : ViewModel() {

  private val message = MutableStateFlow<String?>(null)

  val uiState: StateFlow<CategoryManagementUiState> =
    combine(repository.observeAllCategories(), repository.observeCategoryCounts(), message) {
        categories,
        counts,
        msg ->
        buildState(categories, counts, msg)
      }
      .catch { emit(CategoryManagementUiState(isLoading = false, errorMessage = it.message ?: "加载失败")) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryManagementUiState())

  /**
   * 新建分类。
   *
   * 名称做去空格与重名校验：同一方向上出现两个同名分类，用户在记账时根本分不清该选哪个。
   * 校验放在这里而不是界面，是为了能被单元测试覆盖。
   */
  fun addCategory(name: String, iconKey: String, type: TransactionType) {
    val cleaned = name.trim()
    when {
      cleaned.isEmpty() -> message.value = "分类名不能为空"
      isNameTaken(cleaned, type, exceptId = null) -> message.value = "已存在同名分类「$cleaned」"
      else ->
        viewModelScope.launch {
          repository.addCategory(cleaned, iconKey, type)
          message.value = "已添加「$cleaned」"
        }
    }
  }

  /**
   * 编辑已有分类：名称、图标、方向一起提交。
   *
   * 方向能不能真的改，由仓库层再校验一次——界面上的开关与真正写库之间隔着一次
   * 异步往返，期间完全可能在别处给这个分类记了一笔。
   */
  fun editCategory(id: Long, name: String, iconKey: String, type: TransactionType) {
    val cleaned = name.trim()
    when {
      cleaned.isEmpty() -> message.value = "分类名不能为空"
      isNameTaken(cleaned, type, exceptId = id) ->
        message.value = "已存在同名分类「$cleaned」"
      else ->
        viewModelScope.launch {
          repository.updateCategory(id, cleaned, iconKey)
          val switched = repository.changeCategoryType(id, type)
          message.value =
            if (switched) "已保存，并移到${label(type)}分类" else "已保存「$cleaned」"
        }
    }
  }

  /**
   * 删除自定义分类。
   *
   * 失败原因要说清楚，否则用户只会看到「删不掉」而不知道该改用归档。
   */
  fun deleteCategory(row: CategoryRow) {
    viewModelScope.launch {
      message.value =
        if (repository.deleteCategory(row.id)) "已删除「${row.name}」"
        else "「${row.name}」删不掉：内置分类或名下还有账目，可以改用归档"
    }
  }

  fun archiveCategory(id: Long) {
    viewModelScope.launch {
      repository.archiveCategory(id)
      message.value = "已归档。历史账目仍会保留该分类"
    }
  }

  fun unarchiveCategory(id: Long) {
    viewModelScope.launch {
      repository.unarchiveCategory(id)
      message.value = "已恢复该分类"
    }
  }

  fun onMessageShown() {
    message.value = null
  }

  /**
   * 同一个方向上是否已有同名分类。
   *
   * @param exceptId 编辑时要把自己排除掉，否则「只改图标不改名」会被自己判为重复。
   */
  private fun isNameTaken(name: String, type: TransactionType, exceptId: Long?): Boolean =
    uiState.value.rowsOf(type).any { it.name == name && !it.isArchived && it.id != exceptId }

  private fun label(type: TransactionType): String =
    if (type == TransactionType.EXPENSE) "支出" else "收入"

  private fun buildState(
    categories: List<CategoryEntity>,
    counts: Map<Long, Int>,
    message: String?,
  ): CategoryManagementUiState {
    fun rows(type: TransactionType) =
      categories.filter { it.type == type }.map { category ->
        CategoryRow(
          id = category.id,
          name = category.name,
          iconKey = category.iconKey,
          type = category.type,
          isBuiltIn = category.isBuiltIn,
          isArchived = category.isArchived,
          entryCount = counts[category.id] ?: 0,
        )
      }

    return CategoryManagementUiState(
      isLoading = false,
      expense = rows(TransactionType.EXPENSE),
      income = rows(TransactionType.INCOME),
      message = message,
    )
  }
}
