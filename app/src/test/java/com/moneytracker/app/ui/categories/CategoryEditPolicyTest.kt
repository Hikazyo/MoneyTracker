package com.moneytracker.app.ui.categories

import com.moneytracker.app.data.model.TransactionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「编辑分类时哪些字段能改」的规则。
 *
 * 这些规则直接决定「用户的操作会不会让历史数据变得自相矛盾」，所以单独建模并测试，
 * 而不是写成对话框里的 `if` 只能靠手点界面验证。
 */
class CategoryEditPolicyTest {

  private fun row(
    isBuiltIn: Boolean = false,
    entryCount: Int = 0,
    isArchived: Boolean = false,
  ) = CategoryRow(
    id = 1L,
    name = "测试",
    iconKey = "star",
    type = TransactionType.EXPENSE,
    isBuiltIn = isBuiltIn,
    isArchived = isArchived,
    entryCount = entryCount,
  )

  // ---------- 新建 ----------

  @Test
  fun a_new_category_can_choose_everything() {
    val policy = CategoryEditPolicy.ForNew

    assertTrue(policy.canEditIcon)
    assertTrue(policy.canEditType)
    assertNull(policy.iconLockedReason)
    assertNull(policy.typeLockedReason)
  }

  // ---------- 内置分类 ----------

  @Test
  fun a_built_in_category_locks_its_icon() {
    // 内置分类的 iconKey 是账单导入自动归类的锚点：改了它，导入时那类账目就再也匹配不上
    val policy = CategoryEditPolicy.forExisting(row(isBuiltIn = true))

    assertFalse(policy.canEditIcon)
    assertNotNull("必须说明为什么不能改，而不是默默隐藏", policy.iconLockedReason)
  }

  @Test
  fun a_built_in_category_locks_its_direction() {
    val policy = CategoryEditPolicy.forExisting(row(isBuiltIn = true))

    assertFalse(policy.canEditType)
    assertNotNull(policy.typeLockedReason)
  }

  // ---------- 自定义分类 ----------

  @Test
  fun a_custom_category_with_no_entries_can_change_icon_and_direction() {
    val policy = CategoryEditPolicy.forExisting(row(isBuiltIn = false, entryCount = 0))

    assertTrue(policy.canEditIcon)
    assertTrue(policy.canEditType)
    assertNull(policy.iconLockedReason)
    assertNull(policy.typeLockedReason)
  }

  @Test
  fun a_custom_category_with_entries_can_still_change_its_icon() {
    // 换图标对已有账目没有任何影响，没有理由拦
    val policy = CategoryEditPolicy.forExisting(row(isBuiltIn = false, entryCount = 5))

    assertTrue(policy.canEditIcon)
    assertNull(policy.iconLockedReason)
  }

  @Test
  fun a_custom_category_with_entries_locks_its_direction() {
    // 账目自带方向、分类也自带方向，翻过去会让统计页按方向分组的数字自相矛盾
    val policy = CategoryEditPolicy.forExisting(row(isBuiltIn = false, entryCount = 5))

    assertFalse(policy.canEditType)
    assertNotNull(policy.typeLockedReason)
  }

  @Test
  fun the_direction_lock_reason_states_the_entry_count() {
    // 「不能改」之外还要让人知道为什么，否则用户只会觉得是 bug
    val policy = CategoryEditPolicy.forExisting(row(isBuiltIn = false, entryCount = 7))

    assertTrue(policy.typeLockedReason!!.contains("7 笔"))
  }

  @Test
  fun an_archived_category_is_governed_by_its_entry_count_not_its_archive_state() {
    // 归档只影响它是否出现在记账选项里，与能不能改图标/方向无关
    val empty = CategoryEditPolicy.forExisting(row(isBuiltIn = false, entryCount = 0, isArchived = true))
    assertTrue(empty.canEditType)

    val used = CategoryEditPolicy.forExisting(row(isBuiltIn = false, entryCount = 2, isArchived = true))
    assertFalse(used.canEditType)
  }

  // ---------- 行的可操作性 ----------

  @Test
  fun delete_is_offered_only_for_empty_custom_categories() {
    assertTrue(row(isBuiltIn = false, entryCount = 0).canDelete)
    assertFalse("内置分类是导入归类的依据，不能删", row(isBuiltIn = true, entryCount = 0).canDelete)
    assertFalse("有账目应改用归档，删掉会让账目失去归属", row(isBuiltIn = false, entryCount = 1).canDelete)
  }

  @Test
  fun archive_and_delete_are_never_offered_together() {
    // 两个按钮并列会让用户停下来想「归档和删除差在哪」，而它们的区别只在于能不能撤销
    listOf(0, 1, 5).forEach { count ->
      val custom = row(isBuiltIn = false, entryCount = count)
      assertFalse(
        "entryCount=$count 时归档与删除同时出现",
        custom.canArchive && custom.canDelete,
      )
    }
  }

  @Test
  fun an_empty_custom_category_offers_delete_rather_than_archive() {
    val empty = row(isBuiltIn = false, entryCount = 0)
    assertTrue(empty.canDelete)
    assertFalse(empty.canArchive)
  }

  @Test
  fun a_used_custom_category_offers_archive_rather_than_delete() {
    val used = row(isBuiltIn = false, entryCount = 3)
    assertTrue(used.canArchive)
    assertFalse(used.canDelete)
  }

  @Test
  fun a_built_in_category_offers_neither_archive_nor_delete() {
    val builtIn = row(isBuiltIn = true, entryCount = 0)

    assertFalse(builtIn.canArchive)
    assertFalse(builtIn.canDelete)
  }
}
