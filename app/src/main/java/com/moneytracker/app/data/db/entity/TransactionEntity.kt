package com.moneytracker.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.moneytracker.app.data.model.TransactionType

/** 一笔账目。 */
@Entity(
  tableName = "transactions",
  foreignKeys =
    [
      ForeignKey(
        entity = CategoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        // RESTRICT：有账目引用的分类禁止物理删除，从数据库层兜住「归档而非删除」的规则
        onDelete = ForeignKey.RESTRICT,
      )
    ],
  indices =
    [
      Index(value = ["occurredAt"]),
      Index(value = ["categoryId"]),
      // 唯一索引：导入外部账单时靠它挡住重复导入
      Index(value = ["sourceId"], unique = true),
    ],
)
data class TransactionEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  /** 金额（分），恒为正。收支方向由 [type] 决定。 */
  val amountMinor: Long,
  val type: TransactionType,
  val categoryId: Long,
  val note: String? = null,
  /** 账目发生时间（epoch millis）。归属月份按**本地时区**换算。 */
  val occurredAt: Long,
  val createdAt: Long,
  val updatedAt: Long,
  /**
   * 外部来源标识（导入账单时写入），用于去重。
   *
   * 格式为 `格式:交易单号`，例如 `WECHAT:4200001234567890`。
   * 手工记录的账目为 null；SQLite 的唯一索引允许多个 NULL，因此不受影响。
   */
  val sourceId: String? = null,
)
