package com.moneytracker.app.data.db

import androidx.room.TypeConverter
import com.moneytracker.app.data.model.TransactionType

/**
 * Room 类型转换器。
 *
 * 枚举以**名称字符串**存储，而非 ordinal：ordinal 会随枚举常量的增删或顺序调整而错位，
 * 导致历史数据被解释成完全错误的类型（例如收入变支出）。
 */
class Converters {

  @TypeConverter fun fromTransactionType(value: TransactionType): String = value.name

  @TypeConverter fun toTransactionType(value: String): TransactionType = TransactionType.valueOf(value)
}
