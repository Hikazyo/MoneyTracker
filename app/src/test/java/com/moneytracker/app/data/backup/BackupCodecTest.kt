package com.moneytracker.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

  private val sample =
    BackupFile(
      schemaVersion = BackupCodec.CURRENT_SCHEMA_VERSION,
      exportedAt = 1_700_000_000_000L,
      categories =
        listOf(
          BackupCategory(1, "餐饮", "food", "EXPENSE", 0, isBuiltIn = true, isArchived = false),
          BackupCategory(2, "工资", "salary", "INCOME", 10, isBuiltIn = true, isArchived = false),
        ),
      transactions =
        listOf(
          BackupTransaction(1, 3200, "EXPENSE", 1, "午饭", 1_700_000_000_000L, 1L, 1L),
          BackupTransaction(2, 800_000, "INCOME", 2, null, 1_700_000_100_000L, 2L, 2L),
        ),
      monthlyBudgets = listOf(BackupMonthlyBudget("2026-10", 100_000L, 1L)),
      categoryBudgets = listOf(BackupCategoryBudget("2026-10", 1L, 50_000L, 1L)),
    )

  @Test
  fun round_trip_preserves_every_section() {
    val decoded = BackupCodec.decode(BackupCodec.encode(sample))
    assertTrue(decoded is BackupCodec.DecodeResult.Ok)

    val backup = (decoded as BackupCodec.DecodeResult.Ok).backup
    assertEquals(sample.exportedAt, backup.exportedAt)
    assertEquals(sample.categories, backup.categories)
    assertEquals(sample.transactions, backup.transactions)
    assertEquals(sample.monthlyBudgets, backup.monthlyBudgets)
    assertEquals(sample.categoryBudgets, backup.categoryBudgets)
  }

  @Test
  fun null_note_survives_round_trip() {
    val decoded = BackupCodec.decode(BackupCodec.encode(sample)) as BackupCodec.DecodeResult.Ok
    assertEquals(null, decoded.backup.transactions[1].note)
  }

  @Test
  fun encoded_text_carries_the_schema_version() {
    assertTrue(BackupCodec.encode(sample).contains("\"schemaVersion\": ${BackupCodec.CURRENT_SCHEMA_VERSION}"))
  }

  @Test
  fun newer_schema_version_is_rejected_not_silently_accepted() {
    val future = BackupCodec.encode(sample).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
    val decoded = BackupCodec.decode(future)

    assertTrue(decoded is BackupCodec.DecodeResult.UnsupportedVersion)
    decoded as BackupCodec.DecodeResult.UnsupportedVersion
    assertEquals(99, decoded.found)
    assertEquals(BackupCodec.CURRENT_SCHEMA_VERSION, decoded.expected)
  }

  @Test
  fun older_schema_version_is_also_rejected() {
    val old = BackupCodec.encode(sample).replace("\"schemaVersion\": 1", "\"schemaVersion\": 0")
    assertTrue(BackupCodec.decode(old) is BackupCodec.DecodeResult.UnsupportedVersion)
  }

  @Test
  fun malformed_json_is_reported_as_malformed() {
    assertTrue(BackupCodec.decode("这不是 JSON") is BackupCodec.DecodeResult.Malformed)
    assertTrue(BackupCodec.decode("") is BackupCodec.DecodeResult.Malformed)
    assertTrue(BackupCodec.decode("{ \"schemaVersion\": }") is BackupCodec.DecodeResult.Malformed)
  }

  @Test
  fun missing_required_field_is_reported_as_malformed() {
    // 缺 transactions：结构不完整，必须拒绝而不是当成空备份恢复（那会清空用户数据）
    val incomplete =
      """
      {
        "schemaVersion": ${BackupCodec.CURRENT_SCHEMA_VERSION},
        "exportedAt": 1,
        "categories": []
      }
      """
        .trimIndent()
    assertTrue(BackupCodec.decode(incomplete) is BackupCodec.DecodeResult.Malformed)
  }

  @Test
  fun unknown_fields_are_ignored_for_forward_compatibility() {
    // 未来版本新增字段时，旧版本 App 至少还能读懂自己认识的部分
    val withExtra =
      BackupCodec.encode(sample).replaceFirst("{", "{\n  \"futureField\": \"whatever\",")
    assertTrue(BackupCodec.decode(withExtra) is BackupCodec.DecodeResult.Ok)
  }

  @Test
  fun unknown_transaction_type_parses_into_failure_only_at_restore_time() {
    // 编解码层不校验枚举名（它只负责结构），映射层才抛错并整笔回滚
    val weird =
      BackupCodec.encode(sample).replace("\"type\": \"EXPENSE\"", "\"type\": \"SOMETHING_ELSE\"")
    val decoded = BackupCodec.decode(weird)
    assertTrue("结构上仍然合法，留给恢复阶段拒绝", decoded is BackupCodec.DecodeResult.Ok)
  }
}
