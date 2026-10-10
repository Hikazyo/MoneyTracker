package com.moneytracker.app.data.db

import com.moneytracker.app.data.model.TransactionType
import com.moneytracker.app.ui.components.CategoryVisuals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置分类种子数据的一致性检查。
 *
 * 这些断言看着琐碎，但每一条都对应一种「不会报错、只会让用户困惑」的问题：
 * 两个分类同名、两个分类图标一样、或者某个分类没有对应图标（界面会退化成名称首字）。
 */
class SeedDataTest {

  @Test
  fun category_counts_are_what_we_intend_to_ship() {
    // 钉住数量是刻意的改动成本：以后再加分类会在这里失败，提醒补迁移与关键词规则
    assertEquals(20, SeedData.EXPENSE_CATEGORIES.size)
    assertEquals(8, SeedData.INCOME_CATEGORIES.size)
    assertEquals(28, SeedData.ALL.size)
  }

  @Test
  fun every_category_declares_the_direction_of_its_list() {
    SeedData.EXPENSE_CATEGORIES.forEach {
      assertEquals("${it.name} 出现在支出列表里，方向却是 ${it.type}", TransactionType.EXPENSE, it.type)
    }
    SeedData.INCOME_CATEGORIES.forEach {
      assertEquals("${it.name} 出现在收入列表里，方向却是 ${it.type}", TransactionType.INCOME, it.type)
    }
  }

  @Test
  fun names_are_unique_across_all_categories() {
    // 同名分类会让用户在选择器里看到两个一模一样、却分属不同方向的选项
    val duplicates = SeedData.ALL.groupBy { it.name }.filterValues { it.size > 1 }.keys
    assertTrue("存在重名分类：$duplicates", duplicates.isEmpty())
  }

  @Test
  fun icon_keys_are_unique_across_all_categories() {
    // 图标 key 是账单导入自动归类的依据。两个分类共用一个 key，
    // 导入时 associate 会互相覆盖，账目就会归到错的那个分类里。
    val duplicates = SeedData.ALL.groupBy { it.iconKey }.filterValues { it.size > 1 }.keys
    assertTrue("存在共用 iconKey 的分类：$duplicates", duplicates.isEmpty())
  }

  @Test
  fun every_built_in_icon_key_has_an_emoji() {
    // 漏配的话，界面会悄悄退化成「名称首字」，很难被发现
    val available = CategoryVisuals.iconChoices.map { it.key }.toSet()
    val missing = SeedData.ALL.map { it.iconKey }.filterNot { it in available }
    assertTrue("这些内置分类没有对应图标：$missing", missing.isEmpty())
  }

  // ---------- v1 / v3 的拆分 ----------

  @Test
  fun v1_categories_are_still_a_prefix_of_the_current_list() {
    // 迁移时用 v1Count(type) 作为新内置分类的起始 sortOrder，
    // 这个算法成立的前提就是 v1 那批仍然排在最前面。
    TransactionType.entries.forEach { type ->
      val all = SeedData.categories(type).map { it.name }
      val v1Count = SeedData.v1Count(type)
      val added = SeedData.addedInV3(type).map { it.name }

      assertEquals("v1 之后应当正好接上 v3 新增的那批", added, all.drop(v1Count))
      assertTrue("v1 数量不应超过总数", v1Count <= all.size)
    }
  }

  @Test
  fun v3_additions_are_non_empty_for_both_directions() {
    // 迁移里 `if (additions.isEmpty()) return` 是个提前返回，
    // 万一有人清空了新增列表，迁移会静默什么都不做——这条断言挡住那种情况
    TransactionType.entries.forEach { type ->
      assertTrue("$type 方向应当有 v3 新增分类", SeedData.addedInV3(type).isNotEmpty())
    }
  }

  @Test
  fun the_new_expense_categories_cover_the_gaps_we_meant_to_fill() {
    // 这些是「原来只能塞进购物或『其他支出』、混在一起看不清钱花在哪」的几类
    val names = SeedData.EXPENSE_CATEGORIES.map { it.name }.toSet()
    listOf("服饰", "日用", "数码", "美妆", "运动", "宠物", "母婴", "旅行", "汽车", "订阅")
      .forEach { assertTrue("缺少支出分类「$it」", it in names) }
  }

  @Test
  fun refund_exists_so_imported_refunds_stop_falling_into_the_fallback() {
    // 账单导入会把退款作为独立收入行保留，但退款匹配不上原有的任何收入规则，
    // 没有这个分类时它们会全落进兜底分类，统计上看不出退了多少
    val names = SeedData.INCOME_CATEGORIES.map { it.name }.toSet()
    assertTrue("缺少收入分类「退款」", "退款" in names)
  }
}
