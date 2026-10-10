package com.moneytracker.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.moneytracker.app.data.model.TransactionType

/** 账目分类。 */
@Entity(
  tableName = "categories",
  indices = [Index(value = ["type", "isArchived", "sortOrder"])],
)
data class CategoryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  /**
   * 图标标识。
   *
   * 存语义化 key（如 "food"）而不是 Android 资源 ID：资源 ID 是编译期生成的整数，
   * 跨构建/版本并不稳定，存进数据库后一旦资源变动就会指向错误的图标。
   */
  val iconKey: String,
  val type: TransactionType,
  val sortOrder: Int = 0,
  /** 内置分类不可被用户删除。 */
  val isBuiltIn: Boolean = false,
  /**
   * 归档（软删除）。
   *
   * 分类被账目外键引用，物理删除会破坏历史记录的完整性。用户「删除」分类时实际置此标记：
   * 新记账不再可选，历史账目仍能正确显示分类名。
   */
  val isArchived: Boolean = false,
)
