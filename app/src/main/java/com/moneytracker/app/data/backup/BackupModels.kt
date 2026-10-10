package com.moneytracker.app.data.backup

import kotlinx.serialization.Serializable

/**
 * 备份文件结构。
 *
 * 刻意不直接序列化 Room 实体：备份格式一旦发布就必须稳定，
 * 而实体字段会随数据库演进变化。这层独立的记录类型起到隔离作用。
 */
@Serializable
data class BackupFile(
  /** 备份格式版本。恢复前先校验，不兼容就明确拒绝，而不是硬写进去。 */
  val schemaVersion: Int,
  val exportedAt: Long,
  val categories: List<BackupCategory>,
  val transactions: List<BackupTransaction>,
  val monthlyBudgets: List<BackupMonthlyBudget> = emptyList(),
  val categoryBudgets: List<BackupCategoryBudget> = emptyList(),
)

@Serializable
data class BackupCategory(
  val id: Long,
  val name: String,
  val iconKey: String,
  /** 存枚举名（EXPENSE / INCOME）而不是序号：序号会随枚举顺序调整而错位。 */
  val type: String,
  val sortOrder: Int,
  val isBuiltIn: Boolean,
  val isArchived: Boolean,
)

@Serializable
data class BackupTransaction(
  val id: Long,
  val amountMinor: Long,
  val type: String,
  val categoryId: Long,
  val note: String? = null,
  val occurredAt: Long,
  val createdAt: Long,
  val updatedAt: Long,
)

@Serializable
data class BackupMonthlyBudget(
  val monthKey: String,
  val amountMinor: Long,
  val updatedAt: Long,
)

@Serializable
data class BackupCategoryBudget(
  val monthKey: String,
  val categoryId: Long,
  val amountMinor: Long,
  val updatedAt: Long,
)
