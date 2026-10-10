package com.moneytracker.app.data.importer

import com.moneytracker.app.data.model.TransactionType

/**
 * 按关键字猜分类。
 *
 * 这是**启发式**，不是精确规则：账单里的「交易对方 / 商品」是自由文本，
 * 不可能穷举。因此它只负责给出建议，匹配不上的会落到用户选定的兜底分类，
 * 由用户在导入预览里看到「多少条自动匹配、多少条归兜底」。
 *
 * 支出与收入分开列表，是有意的：「红包」在支出侧属于「人情」，
 * 在收入侧则更接近「红包」这个收入分类，同一套规则会把方向搞混。
 *
 * ## 匹配是「先到先得」，所以顺序就是优先级
 *
 * [guess] 取**第一个**命中的规则，因此**具体的规则必须排在笼统的规则前面**：
 * `汽车`（加油、停车）要排在 `交通` 前面，`订阅`（会员）要排在 `娱乐` 前面，
 * 否则这些词永远被笼统规则截走。
 *
 * 更彻底的一条约束是：**同一方向内一个关键词只能出现在一条规则里**。
 * 这条由单元测试强制（见 `CategoryGuesserTest`）——一旦某个词同时属于两条规则，
 * 实际生效的只是列表顺序，改顺序就会悄悄改变归类结果，而那种 bug 没有任何报错。
 */
object CategoryGuesser {

  private val EXPENSE_RULES: List<Pair<String, List<String>>> =
    listOf(
      // ===== 顺序按「具体程度」排，而不是按新老排。下面每条注释都说明它在挡谁。=====

      // 挡 transport：加油 / 停车原本写在交通规则里
      "car" to listOf("加油", "油费", "停车", "保养", "洗车", "车险", "etc"),
      // 挡 entertainment：会员费原本写在娱乐规则里
      "subscription" to listOf("会员", "订阅", "续费", "包月", "月费", "年费"),
      // 挡 entertainment：旅游 / 门票原本写在娱乐规则里
      "travel" to listOf("酒店", "民宿", "客栈", "住宿", "景点", "门票", "旅游", "度假"),
      // 挡 communication：「游戏充值」是娱乐，不是话费充值
      "entertainment" to listOf("电影", "游戏", "音乐", "视频", "ktv", "娱乐", "演出"),
      // 挡 digital：「手机话费」是通讯，不是买手机
      "communication" to listOf("话费", "流量", "宽带", "通讯", "充值"),
      "digital" to listOf("数码", "电脑", "手机", "耳机", "键盘", "鼠标", "显示器", "相机"),
      // 挡 shopping：服饰 / 数码原本写在购物规则里
      "clothes" to
        listOf("服饰", "服装", "衣服", "衣物", "内衣", "外套", "羽绒服", "鞋", "裤", "帽子", "优衣库", "耐克", "阿迪"),
      // 刻意**不**收「日用」这个词：「日用品」是微信/支付宝给超市购物用的通用商品名，
      // 收进来会把大量超市消费从「购物」抢到「日用」。只认具体品类。
      "daily" to
        listOf("纸巾", "抽纸", "卫生纸", "洗护", "清洁", "洗衣", "牙膏", "洗发", "沐浴", "洗洁精", "垃圾袋"),
      "beauty" to listOf("化妆", "护肤", "美容", "美发", "理发", "面膜", "口红", "指甲"),
      "sport" to
        listOf("健身", "运动", "游泳", "瑜伽", "跑步", "羽毛球", "乒乓球", "篮球", "足球", "球馆"),
      "pet" to listOf("宠物", "猫粮", "狗粮", "猫砂", "兽医"),
      "baby" to listOf("母婴", "奶粉", "尿布", "纸尿裤", "婴儿", "儿童", "玩具"),

      // ===== 以下的通用分类排在最后 =====
      "food" to
        listOf("餐", "饭", "食", "外卖", "美团", "饿了么", "咖啡", "奶茶", "火锅", "烧烤", "快餐", "食堂", "面馆", "小吃"),
      "transport" to
        listOf("地铁", "公交", "出租", "滴滴", "打车", "高速", "高铁", "火车", "机票", "航班", "单车", "交通"),
      "shopping" to
        listOf("超市", "商场", "商城", "淘宝", "天猫", "京东", "拼多多", "便利店", "购物", "百货"),
      "housing" to listOf("房租", "物业", "水费", "电费", "燃气", "暖气", "住房", "租金", "装修"),
      "medical" to listOf("医院", "药房", "药店", "诊所", "体检", "挂号", "医疗", "牙科"),
      "education" to listOf("书店", "课程", "培训", "教育", "学费", "文具", "考试", "图书"),
      "gift" to listOf("红包", "转账", "礼", "人情", "份子"),
    )

  private val INCOME_RULES: List<Pair<String, List<String>>> =
    listOf(
      "salary" to listOf("工资", "薪资", "薪酬", "代发", "月薪"),
      "bonus" to listOf("奖金", "年终奖", "绩效", "提成"),
      "investment" to listOf("收益", "利息", "基金", "理财", "分红", "余额宝"),
      "part_time" to listOf("兼职", "稿费", "外快", "劳务"),
      "red_packet" to listOf("红包", "礼金"),
      // 退款与报销是对账时最容易「看不见」的两类，单独成类
      "refund" to listOf("退款", "退货", "退回", "退差价"),
      "reimbursement" to listOf("报销", "补贴", "垫付"),
    )

  /** 供测试与自检使用：某个方向的全部规则。 */
  internal fun rulesFor(type: TransactionType): List<Pair<String, List<String>>> =
    if (type == TransactionType.EXPENSE) EXPENSE_RULES else INCOME_RULES

  /**
   * 猜测分类的 iconKey。
   *
   * @param type 收支方向，决定用哪一套规则
   * @param texts 参与匹配的文本（交易对方、商品、交易类型等）
   */
  fun guess(type: TransactionType, texts: List<String>): String? {
    val haystack = texts.filter { it.isNotBlank() }.joinToString(" ").lowercase()
    if (haystack.isEmpty()) return null

    val rules = if (type == TransactionType.EXPENSE) EXPENSE_RULES else INCOME_RULES
    return rules.firstOrNull { (_, keywords) -> keywords.any { haystack.contains(it) } }?.first
  }
}
