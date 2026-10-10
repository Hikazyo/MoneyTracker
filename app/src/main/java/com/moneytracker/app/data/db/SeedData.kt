package com.moneytracker.app.data.db

import com.moneytracker.app.data.model.TransactionType

/**
 * 内置分类种子数据。
 *
 * iconKey 是语义化标识，由界面层映射到具体图标。这样图标资源怎么换都不影响数据库内容。
 *
 * ## 为什么分成「v1」与「v3 补充」两份
 *
 * 种子数据**只在首次建库时写入**（`RoomDatabase.Callback.onCreate`）。已经装了 App 的用户
 * 不会重新建库，所以往种子列表里加分类，老用户根本看不到——这是最难查的一类不一致：
 * 新装用户有、升级用户没有，而且没有任何报错。
 *
 * 因此这里刻意保留两份：
 *
 *  - `V1_*`：首版就有的分类，**不要再改动**，已有用户的库里就是这一批
 *  - `ADDED_IN_V3_*`：v3 补充的分类，由 `MIGRATION_2_3` 为老用户补写进去
 *
 * 两份数据各管一条路径（新装 / 升级），再加一条单元测试断言「并集就是当前完整列表」，
 * 就不会出现两边走样。
 */
object SeedData {

  data class SeedCategory(val name: String, val iconKey: String, val type: TransactionType)

  // ---------- v1：首版内置分类（冻结，不要修改） ----------

  private val V1_EXPENSE =
    listOf(
      SeedCategory("餐饮", "food", TransactionType.EXPENSE),
      SeedCategory("交通", "transport", TransactionType.EXPENSE),
      SeedCategory("购物", "shopping", TransactionType.EXPENSE),
      SeedCategory("居住", "housing", TransactionType.EXPENSE),
      SeedCategory("娱乐", "entertainment", TransactionType.EXPENSE),
      SeedCategory("医疗", "medical", TransactionType.EXPENSE),
      SeedCategory("学习", "education", TransactionType.EXPENSE),
      SeedCategory("通讯", "communication", TransactionType.EXPENSE),
      SeedCategory("人情", "gift", TransactionType.EXPENSE),
      SeedCategory("其他支出", "other_expense", TransactionType.EXPENSE),
    )

  private val V1_INCOME =
    listOf(
      SeedCategory("工资", "salary", TransactionType.INCOME),
      SeedCategory("奖金", "bonus", TransactionType.INCOME),
      SeedCategory("理财收益", "investment", TransactionType.INCOME),
      SeedCategory("兼职", "part_time", TransactionType.INCOME),
      SeedCategory("红包", "red_packet", TransactionType.INCOME),
      SeedCategory("其他收入", "other_income", TransactionType.INCOME),
    )

  // ---------- v3 补充 ----------

  /**
   * 补充的支出分类。
   *
   * 挑选标准是「原来只能塞进『购物』『其他支出』但实际发生频率很高、且混在一起会看不清
   * 钱花在哪」的几类。顺序即展示顺序，按大致使用频率排。
   */
  private val ADDED_IN_V3_EXPENSE =
    listOf(
      SeedCategory("服饰", "clothes", TransactionType.EXPENSE),
      SeedCategory("日用", "daily", TransactionType.EXPENSE),
      SeedCategory("数码", "digital", TransactionType.EXPENSE),
      SeedCategory("美妆", "beauty", TransactionType.EXPENSE),
      SeedCategory("运动", "sport", TransactionType.EXPENSE),
      SeedCategory("宠物", "pet", TransactionType.EXPENSE),
      SeedCategory("母婴", "baby", TransactionType.EXPENSE),
      SeedCategory("旅行", "travel", TransactionType.EXPENSE),
      SeedCategory("汽车", "car", TransactionType.EXPENSE),
      SeedCategory("订阅", "subscription", TransactionType.EXPENSE),
    )

  /**
   * 补充的收入分类。
   *
   * 「退款」不是凑数：账单导入会把退款行当作独立的收入行保留（见 `DESIGN.md` 五），
   * 而退款又匹配不上原有的任何收入规则，结果全落进兜底分类，统计上看不出退了多少。
   */
  private val ADDED_IN_V3_INCOME =
    listOf(
      SeedCategory("退款", "refund", TransactionType.INCOME),
      SeedCategory("报销", "reimbursement", TransactionType.INCOME),
    )

  // ---------- 对外 ----------

  val EXPENSE_CATEGORIES: List<SeedCategory> = V1_EXPENSE + ADDED_IN_V3_EXPENSE

  val INCOME_CATEGORIES: List<SeedCategory> = V1_INCOME + ADDED_IN_V3_INCOME

  /** 当前完整的分类列表，新装用户建库时写入。 */
  val ALL: List<SeedCategory> = EXPENSE_CATEGORIES + INCOME_CATEGORIES

  /** 某个方向的完整分类列表。 */
  fun categories(type: TransactionType): List<SeedCategory> =
    if (type == TransactionType.EXPENSE) EXPENSE_CATEGORIES else INCOME_CATEGORIES

  /** 某个方向上 v3 才补充的分类，迁移用。 */
  fun addedInV3(type: TransactionType): List<SeedCategory> =
    if (type == TransactionType.EXPENSE) ADDED_IN_V3_EXPENSE else ADDED_IN_V3_INCOME

  /** 某个方向上首版就有的分类数量，即 v3 迁移时新分类的起始 sortOrder。 */
  fun v1Count(type: TransactionType): Int =
    if (type == TransactionType.EXPENSE) V1_EXPENSE.size else V1_INCOME.size
}
