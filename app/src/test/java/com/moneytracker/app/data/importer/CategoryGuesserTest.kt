package com.moneytracker.app.data.importer

import com.moneytracker.app.data.db.SeedData
import com.moneytracker.app.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类猜测规则测试。
 *
 * 两条结构性断言是重点（关键词唯一、规则指向真实存在的分类）：
 * 它们把「改顺序就会悄悄改变归类结果」和「规则指向一个不存在的分类」这两类
 * **不会报错**的问题变成了编译后立刻可见的失败。
 */
class CategoryGuesserTest {

  private fun guessExpense(vararg texts: String): String? =
    CategoryGuesser.guess(TransactionType.EXPENSE, texts.toList())

  private fun guessIncome(vararg texts: String): String? =
    CategoryGuesser.guess(TransactionType.INCOME, texts.toList())

  // ---------- 结构性约束 ----------

  @Test
  fun a_keyword_belongs_to_only_one_rule_per_direction() {
    // 匹配是「先到先得」，所以同一个词出现在两条规则里时，实际生效的只是列表顺序。
    // 那种 bug 没有任何报错，只在用户发现「怎么归到这类了」时才暴露。
    TransactionType.entries.forEach { type ->
      val seen = mutableMapOf<String, String>()
      val clashes = mutableListOf<String>()
      CategoryGuesser.rulesFor(type).forEach { (iconKey, keywords) ->
        keywords.forEach { keyword ->
          val previous = seen.put(keyword, iconKey)
          if (previous != null) clashes.add("「$keyword」同时属于 $previous 与 $iconKey")
        }
      }
      assertTrue("$type 方向存在重复关键词：$clashes", clashes.isEmpty())
    }
  }

  @Test
  fun every_rule_points_at_a_category_we_actually_ship() {
    // 规则指向不存在的 iconKey 时，导入会把这类账目判为「未匹配」丢给兜底分类，
    // 表面上一切正常，只是永远分不对类
    val builtInKeys = SeedData.ALL.map { it.iconKey }.toSet()
    TransactionType.entries.forEach { type ->
      val unknown =
        CategoryGuesser.rulesFor(type).map { it.first }.filterNot { it in builtInKeys }
      assertTrue("$type 方向的规则指向了不存在的分类：$unknown", unknown.isEmpty())
    }
  }

  // ---------- 新增的细分分类 ----------

  @Test
  fun the_new_expense_categories_are_reachable_by_their_own_keywords() {
    assertEquals("clothes", guessExpense("优衣库"))
    assertEquals("daily", guessExpense("洗衣液"))
    assertEquals("digital", guessExpense("蓝牙耳机"))
    assertEquals("beauty", guessExpense("理发店"))
    assertEquals("sport", guessExpense("健身房"))
    assertEquals("pet", guessExpense("猫粮"))
    assertEquals("baby", guessExpense("奶粉"))
    assertEquals("travel", guessExpense("某某酒店"))
    assertEquals("car", guessExpense("加油费"))
    assertEquals("subscription", guessExpense("视频会员"))
  }

  @Test
  fun a_supermarket_row_still_counts_as_shopping() {
    // 「日用品」是微信/支付宝给超市购物用的**通用商品名**，几乎每笔超市消费都长这样。
    // 它一旦被当成「日用」的关键词，就会把大量超市消费从购物抢走——
    // 所以 daily 规则里刻意不收「日用」这个词。
    assertEquals("shopping", guessExpense("某某超市", "日用品"))
    assertEquals("shopping", guessExpense("永辉超市"))
    // 但具体品类仍然归到日用
    assertEquals("daily", guessExpense("某某超市", "抽纸"))
  }

  @Test
  fun refunds_and_reimbursements_have_their_own_income_categories() {
    assertEquals("refund", guessIncome("退款"))
    assertEquals("refund", guessIncome("退货"))
    assertEquals("reimbursement", guessIncome("差旅报销"))
  }

  // ---------- 新分类必须赢过原本会截走它们的笼统规则 ----------

  @Test
  fun car_wins_over_transport_for_fuel_and_parking() {
    // 「加油」「停车」原本写在交通规则里。新的汽车分类如果排在交通后面就永远轮不到它。
    assertEquals("car", guessExpense("中石化加油"))
    assertEquals("car", guessExpense("地下停车场"))
  }

  @Test
  fun subscription_wins_over_entertainment_for_membership_fees() {
    // 「会员」原本写在娱乐规则里
    assertEquals("subscription", guessExpense("某某视频会员"))
  }

  @Test
  fun travel_wins_over_entertainment_for_hotels_and_tickets() {
    // 「旅游」「门票」原本写在娱乐规则里
    assertEquals("travel", guessExpense("景区门票"))
    assertEquals("travel", guessExpense("某某旅游"))
  }

  @Test
  fun specific_expense_categories_win_over_the_generic_shopping_rule() {
    // 「服饰」「数码」原本写在购物规则里
    assertEquals("clothes", guessExpense("服饰旗舰店"))
    assertEquals("digital", guessExpense("数码专营店"))
  }

  // ---------- 不能误伤 ----------

  @Test
  fun a_cinema_named_after_a_cat_is_not_a_pet_expense() {
    // 「猫眼电影」——所以宠物规则里刻意不写单个「猫」字
    assertEquals("entertainment", guessExpense("猫眼电影"))
  }

  @Test
  fun food_and_transport_still_work_after_their_keywords_were_narrowed() {
    assertEquals("food", guessExpense("某某餐厅"))
    assertEquals("transport", guessExpense("地铁"))
    assertEquals("transport", guessExpense("高铁票"))
    assertEquals("communication", guessExpense("手机话费"))
  }

  @Test
  fun a_phone_bill_is_not_mistaken_for_buying_a_phone() {
    // communication 必须排在 digital 前面：两者的关键词在「手机话费」上会同时命中
    assertEquals("communication", guessExpense("中国移动 手机话费"))
    // 而单纯买手机仍然归数码
    assertEquals("digital", guessExpense("某某数码", "手机"))
  }

  @Test
  fun topping_up_a_game_is_entertainment_not_a_phone_top_up() {
    // entertainment 必须排在 communication 前面：「充值」两边都有
    assertEquals("entertainment", guessExpense("游戏充值"))
  }

  @Test
  fun the_same_word_can_mean_different_things_per_direction() {
    // 「红包」在支出侧是人情，在收入侧是红包：两套规则分开正是为了这个
    assertEquals("gift", guessExpense("给同事红包"))
    assertEquals("red_packet", guessIncome("收到红包"))
  }

  @Test
  fun blank_input_yields_no_suggestion() {
    assertNull(guessExpense("", "   "))
    assertNull(CategoryGuesser.guess(TransactionType.EXPENSE, emptyList()))
  }

  @Test
  fun matching_is_case_insensitive_for_latin_keywords() {
    assertEquals("car", guessExpense("ETC 通行费"))
    assertEquals("entertainment", guessExpense("KTV"))
  }
}
