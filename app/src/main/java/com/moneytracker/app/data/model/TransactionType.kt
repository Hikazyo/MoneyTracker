package com.moneytracker.app.data.model

/**
 * 账目方向。
 *
 * 金额本身恒为正数，收支方向完全由本字段决定。这样避免「负数 + 类型」双重语义——
 * 否则每处统计都要判断符号，极易出错。
 */
enum class TransactionType {
  EXPENSE,
  INCOME,
}
