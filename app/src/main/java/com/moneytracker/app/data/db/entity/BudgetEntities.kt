package com.moneytracker.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 月度总预算。 */
@Entity(tableName = "monthly_budgets")
data class MonthlyBudgetEntity(
  /** 形如 "2026-10" */
  @androidx.room.PrimaryKey val monthKey: String,
  val amountMinor: Long,
  val updatedAt: Long,
)

/**
 * 分类预算。
 *
 * 为什么不做成「单表 + 可空 categoryId（NULL 表示总预算）」：
 * SQLite 的 UNIQUE 约束对 NULL 不生效——多行 NULL 可以共存，于是同一个月的总预算
 * 会被插入出重复行。要修就得用部分唯一索引（WHERE categoryId IS NULL），而 Room 的
 * @Index 注解不支持部分索引，只能写裸 SQL。
 *
 * 拆成独立表后：复合主键天然保证唯一、外键能正常生效、无 NULL 语义陷阱。
 */
@Entity(
  tableName = "category_budgets",
  primaryKeys = ["monthKey", "categoryId"],
  foreignKeys =
    [
      ForeignKey(
        entity = CategoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
  indices = [Index(value = ["categoryId"])],
)
data class CategoryBudgetEntity(
  val monthKey: String,
  val categoryId: Long,
  val amountMinor: Long,
  val updatedAt: Long,
)
