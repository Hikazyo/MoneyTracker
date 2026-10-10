package com.moneytracker.app.data.backup

import com.moneytracker.app.data.db.entity.CategoryBudgetEntity
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.db.entity.MonthlyBudgetEntity
import com.moneytracker.app.data.db.entity.TransactionEntity
import com.moneytracker.app.data.model.TransactionType

/**
 * 实体与备份记录之间的映射。
 *
 * 枚举名解析失败时**抛异常**，而不是回退到某个默认值：恢复是破坏性操作，
 * 宁可整笔回滚并明确报错，也不要静默地把一笔收入恢复成支出。
 * 抛出的异常会被恢复逻辑捕获，转成用户可见的失败结果。
 */
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

internal fun BackupCategory.toEntity(): CategoryEntity =
  CategoryEntity(
    id = id,
    name = name,
    iconKey = iconKey,
    type = parseTransactionType(type),
    sortOrder = sortOrder,
    isBuiltIn = isBuiltIn,
    isArchived = isArchived,
  )

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

internal fun BackupTransaction.toEntity(): TransactionEntity =
  TransactionEntity(
    id = id,
    amountMinor = amountMinor,
    type = parseTransactionType(type),
    categoryId = categoryId,
    note = note,
    occurredAt = occurredAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

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
