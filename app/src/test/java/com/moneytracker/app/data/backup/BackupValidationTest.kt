package com.moneytracker.app.data.backup

import com.moneytracker.app.data.model.Money
import com.moneytracker.app.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复备份时的字段校验。
 *
 * 恢复是「先清空再写入」的覆盖操作，而备份文件来自用户选择，**可能损坏，也可能被人为构造**。
 * 这里逐条钉住「坏数据进不了账本」——否则用户会以为恢复成功了，实际账本已经不可信。
 */
class BackupValidationTest {

  private fun category(
    id: Long = 1L,
    name: String = "餐饮",
    type: String = "EXPENSE",
  ) = BackupCategory(id, name, "food", type, 0, isBuiltIn = false, isArchived = false)

  private fun transaction(
    id: Long = 1L,
    amountMinor: Long = 3250L,
    type: String = "EXPENSE",
    categoryId: Long = 1L,
    note: String? = "午饭",
    occurredAt: Long = 1_700_000_000_000L,
  ) = BackupTransaction(id, amountMinor, type, categoryId, note, occurredAt, 1L, 1L)

  // ---------- 通过校验的正常数据 ----------

  @Test
  fun valid_records_pass_through() {
    val entity = transaction().toEntity()

    assertEquals(3250L, entity.amountMinor)
    assertEquals(TransactionType.EXPENSE, entity.type)
    assertEquals("午饭", entity.note)
  }

  // ---------- 金额 ----------

  @Test
  fun a_negative_amount_is_rejected() {
    // 金额恒为正、方向由 type 决定——这是全项目不变的约定。
    // 放进一个负数，之后所有统计都会算错。
    val error = runCatching { transaction(amountMinor = -100L).toEntity() }.exceptionOrNull()

    assertTrue("负数金额必须被拒绝，实际：$error", error is IllegalArgumentException)
  }

  @Test
  fun a_zero_amount_is_rejected() {
    val error = runCatching { transaction(amountMinor = 0L).toEntity() }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
  }

  @Test
  fun an_amount_beyond_the_limit_is_rejected() {
    val error =
      runCatching { transaction(amountMinor = Money.MAX_MINOR + 1).toEntity() }.exceptionOrNull()

    assertTrue("超出上限的金额必须被拒绝", error is IllegalArgumentException)
  }

  // ---------- 时间 ----------

  @Test
  fun a_missing_timestamp_is_rejected() {
    val error = runCatching { transaction(occurredAt = 0L).toEntity() }.exceptionOrNull()

    assertTrue("时间为 0 会让账目落到 1970 年", error is IllegalArgumentException)
  }

  @Test
  fun an_absurd_timestamp_is_rejected() {
    // 远超合理范围的时间会把列表与统计彻底打乱。
    // 8_000_000_000_000 ms ≈ 2223 年，已经越过允许的上界（2200）
    val error = runCatching { transaction(occurredAt = 8_000_000_000_000L).toEntity() }.exceptionOrNull()

    assertTrue("公元 2200 年之后的账目应被拒绝，实际：$error", error is IllegalArgumentException)
  }

  // ---------- 文本 ----------

  @Test
  fun an_overlong_note_is_truncated_rather_than_rejected() {
    // 备注过长不构成「数据不可信」，截断即可——直接拒绝会让用户完全无法恢复
    val entity = transaction(note = "备".repeat(MAX_NOTE_LENGTH + 500)).toEntity()

    assertEquals(MAX_NOTE_LENGTH, entity.note!!.length)
  }

  @Test
  fun an_overlong_category_name_is_rejected() {
    val error =
      runCatching { category(name = "分".repeat(MAX_CATEGORY_NAME_LENGTH + 1)).toEntity() }
        .exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
  }

  @Test
  fun a_blank_category_name_is_rejected() {
    val error = runCatching { category(name = "   ").toEntity() }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
  }

  // ---------- 枚举 ----------

  @Test
  fun an_unknown_direction_is_rejected() {
    // 不能回退到默认值：静默把一笔收入恢复成支出是最坏的结果
    val error = runCatching { transaction(type = "TRANSFER").toEntity() }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
  }

  @Test
  fun an_unknown_category_direction_is_rejected() {
    val error = runCatching { category(type = "???").toEntity() }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
  }
}
