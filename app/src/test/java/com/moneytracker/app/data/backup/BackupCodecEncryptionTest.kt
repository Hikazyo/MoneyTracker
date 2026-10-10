package com.moneytracker.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份加密的测试。
 *
 * 加密代码有个特点：**写错了往往也「看起来能跑」**。比如解密时忽略了 GCM 的
 * 认证标签，程序照样能跑出一段字节，只是内容是垃圾——直到用户拿它去覆盖账本才出事。
 * 所以这里逐条盯住「错误必须被拒绝」。
 */
class BackupCodecEncryptionTest {

  private val sample =
    BackupFile(
      schemaVersion = BackupCodec.CURRENT_SCHEMA_VERSION,
      exportedAt = 1_700_000_000_000L,
      categories =
        listOf(
          BackupCategory(1, "餐饮", "food", "EXPENSE", 0, isBuiltIn = true, isArchived = false)
        ),
      transactions = listOf(BackupTransaction(1, 3250, "EXPENSE", 1, "午饭", 1L, 1L, 1L)),
    )

  // ---------- 加密与解密往返 ----------

  @Test
  fun an_encrypted_backup_round_trips() {
    val encrypted = BackupCodec.encode(sample, "正确的口令")

    assertTrue("应当被识别为加密备份", BackupCodec.isEncrypted(encrypted))
    val decoded = BackupCodec.decode(encrypted, "正确的口令")
    assertEquals(sample, (decoded as BackupCodec.DecodeResult.Ok).backup)
  }

  @Test
  fun an_unencrypted_backup_is_not_marked_as_encrypted() {
    val plain = BackupCodec.encode(sample)

    assertFalse(BackupCodec.isEncrypted(plain))
    assertEquals(sample, (BackupCodec.decode(plain) as BackupCodec.DecodeResult.Ok).backup)
  }

  @Test
  fun the_ciphertext_does_not_leak_the_ledger_content() {
    // 最基本的一条：备注与分类名不能以明文出现在加密备份里
    val encrypted = BackupCodec.encode(sample, "口令")

    assertFalse("备注不能出现在密文里", encrypted.contains("午饭"))
    assertFalse("分类名不能出现在密文里", encrypted.contains("餐饮"))
    assertFalse("金额不能出现在密文里", encrypted.contains("3250"))
  }

  // ---------- 错误必须被拒绝 ----------

  @Test
  fun the_wrong_password_is_rejected_instead_of_returning_garbage() {
    val encrypted = BackupCodec.encode(sample, "正确的口令")

    val decoded = BackupCodec.decode(encrypted, "错误的口令")

    assertEquals(BackupCodec.DecodeResult.WrongPassword, decoded)
  }

  @Test
  fun an_encrypted_backup_without_a_password_asks_for_one() {
    val encrypted = BackupCodec.encode(sample, "口令")

    assertEquals(BackupCodec.DecodeResult.PasswordRequired, BackupCodec.decode(encrypted))
    assertEquals(BackupCodec.DecodeResult.PasswordRequired, BackupCodec.decode(encrypted, ""))
  }

  @Test
  fun tampering_with_the_payload_is_detected() {
    // AES-GCM 的认证标签就是为这种情况准备的：
    // 密文被改动一个字符，解密必须失败，而不是解出一段垃圾数据
    val encrypted = BackupCodec.encode(sample, "口令")
    val tampered = encrypted.replaceFirst(Regex("\"payload\": \"(.{20})"), "\"payload\": \"AAAAAAAAAAAAAAAAAAAA")

    assertTrue("前提：确实改到了 payload", tampered != encrypted)
    assertEquals(BackupCodec.DecodeResult.WrongPassword, BackupCodec.decode(tampered, "口令"))
  }

  @Test
  fun tampering_with_the_iteration_count_does_not_silently_succeed() {
    // 迭代次数是解密参数的一部分：把它改小会派生出错误的密钥，解密必须失败。
    // （这条同时说明「迭代次数必须随文件保存」这个设计是必要的）
    val encrypted = BackupCodec.encode(sample, "口令")
    val tampered = encrypted.replace("\"iterations\": 200000", "\"iterations\": 1000")

    assertTrue("前提：确实改到了迭代次数", tampered != encrypted)
    assertEquals(BackupCodec.DecodeResult.WrongPassword, BackupCodec.decode(tampered, "口令"))
  }

  @Test
  fun a_truncated_encrypted_backup_is_rejected() {
    val encrypted = BackupCodec.encode(sample, "口令")

    val decoded = BackupCodec.decode(encrypted.take(encrypted.length / 2), "口令")

    assertTrue(
      "截断的文件不能解出数据，实际是 $decoded",
      decoded is BackupCodec.DecodeResult.Malformed ||
        decoded == BackupCodec.DecodeResult.WrongPassword,
    )
  }

  // ---------- 信封本身 ----------

  @Test
  fun the_envelope_records_the_kdf_parameters() {
    // 参数必须随文件走，否则将来调整迭代次数会让所有旧备份失效
    val encrypted = BackupCodec.encode(sample, "口令")

    assertTrue("要记录 KDF 算法", encrypted.contains("PBKDF2"))
    assertTrue("要记录加密算法", encrypted.contains("AES/GCM"))
    assertNotNull("要记录迭代次数", BackupCodec.iterationsOf(encrypted))
    assertTrue("要带盐", encrypted.contains("\"salt\""))
    assertTrue("要带 IV", encrypted.contains("\"iv\""))
  }

  @Test
  fun the_same_content_encrypted_twice_produces_different_files() {
    // 每次都用新的 salt 与 IV。相同则说明用了固定值，会泄露「两份备份内容一致」这个信息，
    // 也让彩虹表式的攻击成为可能
    val first = BackupCodec.encode(sample, "口令")
    val second = BackupCodec.encode(sample, "口令")

    assertTrue("两次加密的结果不应相同", first != second)
    assertEquals(
      sample,
      (BackupCodec.decode(first, "口令") as BackupCodec.DecodeResult.Ok).backup,
    )
    assertEquals(
      sample,
      (BackupCodec.decode(second, "口令") as BackupCodec.DecodeResult.Ok).backup,
    )
  }

  @Test
  fun an_empty_password_means_plaintext() {
    // 界面允许用户明确选择不加密，此时走的是原有明文路径
    val decoded = BackupCodec.decode(BackupCodec.encode(sample, ""), "")

    assertEquals(sample, (decoded as BackupCodec.DecodeResult.Ok).backup)
    assertFalse(BackupCodec.isEncrypted(BackupCodec.encode(sample, null)))
  }

  @Test
  fun a_damaged_plaintext_backup_is_still_reported_as_malformed() {
    // 不加密路径的既有行为不能被这次改动破坏
    val decoded = BackupCodec.decode("{ 这不是 JSON }")

    assertTrue(decoded is BackupCodec.DecodeResult.Malformed)
  }
}
