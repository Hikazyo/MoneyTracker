package com.moneytracker.app.data.backup

import com.moneytracker.app.data.db.entity.CategoryBudgetEntity
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.db.entity.MonthlyBudgetEntity
import com.moneytracker.app.data.db.entity.TransactionEntity
import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.model.TransactionType
import java.time.Instant
import java.time.ZoneOffset

/**
 * 实体与备份记录之间的映射。
 *
 * 枚举名解析失败时**抛异常**，而不是回退到某个默认值：恢复是破坏性操作，
 * 宁可整笔回滚并明确报错，也不要静默地把一笔收入恢复成支出。
 * 抛出的异常会被恢复逻辑捕获，转成用户可见的失败结果。
 *
 * ## 为什么恢复时要校验字段
 *
 * 恢复流程是「先清空、再写入」的覆盖操作。备份文件来自用户选择，**可能是损坏的、
 * 也可能被人为构造过**。如果不校验就把里面的值写进去：
 *
 *  - `amountMinor` 为负 → 违反「金额恒为正、方向由 type 决定」这条全项目不变的约定，
 *    之后所有统计都会算错
 *  - `occurredAt` 为 0 或极端值 → 账目落到 1970 年或公元 5 万年，列表与统计彻底乱掉
 *  - 超长文本 → 数据库与备份文件被撑大，渲染变慢
 *  - `categoryId` 指向不存在的分类 → 账目失去归属（外键会把整次恢复顶回来）
 *
 * 校验失败一律**抛异常让整次恢复回滚**，而不是「跳过坏数据继续写」——
 * 后者会让用户以为恢复成功了，却少了一部分账目。
 */

/** 备注长度上限。真实备注远短于此，设上限是为了挡住畸形文件把库撑大。 */
internal const val MAX_NOTE_LENGTH = 500

/** 分类名长度上限。 */
internal const val MAX_CATEGORY_NAME_LENGTH = 30

/** 允许的年份范围。账单不会早于 1970，也不会来自未来太远。 */
private const val MIN_YEAR = 1970
private const val MAX_YEAR = 2200

internal fun parseTransactionType(raw: String): TransactionType =
  TransactionType.entries.firstOrNull { it.name == raw }
    ?: throw IllegalArgumentException("备份文件中的账目类型无法识别：$raw")

internal fun CategoryEntity.toBackup(): BackupCategory =
  BackupCategory(
    id = id,
    name = name,
    iconKey = iconKey,
    type = type.name,
    sortOrder = sortOrder,
    isBuiltIn = isBuiltIn,
    isArchived = isArchived,
  )

internal fun BackupCategory.toEntity(): CategoryEntity {
  require(name.isNotBlank()) { "备份文件里有分类没有名字" }
  require(name.length <= MAX_CATEGORY_NAME_LENGTH) {
    "备份文件里的分类名过长（${name.length} 字符，上限 $MAX_CATEGORY_NAME_LENGTH）"
  }
  return CategoryEntity(
    id = id,
    name = name,
    iconKey = iconKey,
    type = parseTransactionType(type),
    sortOrder = sortOrder,
    isBuiltIn = isBuiltIn,
    isArchived = isArchived,
  )
}

internal fun TransactionEntity.toBackup(): BackupTransaction =
  BackupTransaction(
    id = id,
    amountMinor = amountMinor,
    type = type.name,
    categoryId = categoryId,
    note = note,
    occurredAt = occurredAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

internal fun BackupTransaction.toEntity(): TransactionEntity {
  require(amountMinor > 0L) { "备份文件里有金额不是正数的账目（$amountMinor）" }
  require(amountMinor <= Money.MAX_MINOR) { "备份文件里有金额超出上限的账目（$amountMinor）" }
  require(occurredAt > 0L) { "备份文件里有缺少时间的账目" }

  val year = Instant.ofEpochMilli(occurredAt).atZone(ZoneOffset.UTC).year
  require(year in MIN_YEAR..MAX_YEAR) { "备份文件里有时间异常（$year 年）的账目" }

  val trimmedNote = note?.take(MAX_NOTE_LENGTH)
  return TransactionEntity(
    id = id,
    amountMinor = amountMinor,
    type = parseTransactionType(type),
    categoryId = categoryId,
    note = trimmedNote,
    occurredAt = occurredAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )
}

internal fun MonthlyBudgetEntity.toBackup(): BackupMonthlyBudget =
  BackupMonthlyBudget(monthKey = monthKey, amountMinor = amountMinor, updatedAt = updatedAt)

internal fun BackupMonthlyBudget.toEntity(): MonthlyBudgetEntity =
  MonthlyBudgetEntity(monthKey = monthKey, amountMinor = amountMinor, updatedAt = updatedAt)

internal fun CategoryBudgetEntity.toBackup(): BackupCategoryBudget =
  BackupCategoryBudget(
    monthKey = monthKey,
    categoryId = categoryId,
    amountMinor = amountMinor,
    updatedAt = updatedAt,
  )

internal fun BackupCategoryBudget.toEntity(): CategoryBudgetEntity =
  CategoryBudgetEntity(
    monthKey = monthKey,
    categoryId = categoryId,
    amountMinor = amountMinor,
    updatedAt = updatedAt,
  )
