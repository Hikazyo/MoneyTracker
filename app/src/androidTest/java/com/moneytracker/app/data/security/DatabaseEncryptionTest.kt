package com.moneytracker.app.data.security

import android.content.Context
import android.database.sqlite.SQLiteDatabase as FrameworkDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 明文数据库 → 加密数据库的迁移。
 *
 * 这是整个加密方案里**唯一可能弄丢用户数据**的一步，所以测得比别处细：
 * 既要验证「迁移成功且数据完整」，也要验证「迁移失败时原库原封不动」。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionTest {

  private lateinit var context: Context
  private val passphrase = "0123456789abcdef0123456789abcdef"

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    DatabaseEncryption.loadLibrary()
  }

  @After
  fun tearDown() {
    testDatabaseNames.forEach { name ->
      val file = context.getDatabasePath(name)
      file.delete()
      File(file.path + ".encrypted.tmp").delete()
      File(file.path + ".plaintext.bak").delete()
      File(file.path + "-wal").delete()
      File(file.path + "-shm").delete()
      File(file.path + "-journal").delete()
    }
  }

  private val testDatabaseNames = mutableListOf<String>()

  private fun newDatabaseName(): String =
    "encryption-test-${System.nanoTime()}.db".also { testDatabaseNames.add(it) }

  /** 造一个**明文**库，模拟 v1.2 及更早版本留下的数据。 */
  private fun createPlaintextDatabase(
    name: String,
    transactions: Int,
    categories: Int,
    schemaVersion: Int = 3,
  ) {
    val file = context.getDatabasePath(name)
    file.parentFile?.mkdirs()
    FrameworkDatabase.openOrCreateDatabase(file, null).use { db ->
      db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
      db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, amountMinor INTEGER)")
      repeat(categories) { db.execSQL("INSERT INTO categories (name) VALUES (?)", arrayOf("分类$it")) }
      repeat(transactions) { db.execSQL("INSERT INTO transactions (amountMinor) VALUES (100)") }
      // 模拟 Room 建库后写入的版本号。这一条是本次加密改造里最关键的一处：
      // sqlcipher_export 不复制 user_version，丢了它 Room 会把已有库当成新库、重跑种子回调。
      db.execSQL("PRAGMA user_version = $schemaVersion")
    }
  }

  private fun userVersionOf(file: File, key: String): Int =
    CipherDatabase.openDatabase(file.path, key, null, CipherDatabase.OPEN_READONLY, null).use { db ->
      db.rawQuery("PRAGMA user_version", emptyArray()).use {
        it.moveToFirst()
        it.getInt(0)
      }
    }

  private fun countRows(file: File, key: String): Pair<Int, Int> =
    CipherDatabase.openDatabase(file.path, key, null, CipherDatabase.OPEN_READONLY, null).use { db ->
      val transactions =
        db.rawQuery("SELECT COUNT(*) FROM transactions", emptyArray()).use {
          it.moveToFirst()
          it.getInt(0)
        }
      val categories =
        db.rawQuery("SELECT COUNT(*) FROM categories", emptyArray()).use {
          it.moveToFirst()
          it.getInt(0)
        }
      transactions to categories
    }

  // ---------- 明文判定 ----------

  @Test
  fun a_plaintext_database_is_recognized() {
    val name = newDatabaseName()
    createPlaintextDatabase(name, transactions = 1, categories = 1)

    assertTrue(DatabaseEncryption.isPlaintext(context.getDatabasePath(name)))
  }

  @Test
  fun a_missing_file_counts_as_plaintext() {
    // 全新安装：没有文件，也就无所谓加不加密
    assertTrue(DatabaseEncryption.isPlaintext(context.getDatabasePath(newDatabaseName())))
  }

  // ---------- 迁移成功路径 ----------

  @Test
  fun a_plaintext_database_is_migrated_without_losing_rows() {
    val name = newDatabaseName()
    createPlaintextDatabase(name, transactions = 7, categories = 3)

    val result = DatabaseEncryption.migrateIfNeeded(context, name, passphrase)

    assertEquals(
      "应报告迁移了 7 笔账目与 3 个分类",
      EncryptionMigrationResult.Migrated(transactionRows = 7, categoryRows = 3),
      result,
    )

    val file = context.getDatabasePath(name)
    assertFalse("迁移后不应再被判定为明文", DatabaseEncryption.isPlaintext(file))
    assertEquals("行数必须与迁移前一致", 7 to 3, countRows(file, passphrase))
  }

  @Test
  fun after_migration_the_old_plaintext_password_no_longer_opens_the_file() {
    val name = newDatabaseName()
    createPlaintextDatabase(name, transactions = 2, categories = 1)
    DatabaseEncryption.migrateIfNeeded(context, name, passphrase)

    // 用空口令（= 明文模式）去开必须失败，否则说明文件其实没被加密
    val openedAsPlaintext =
      runCatching {
        CipherDatabase.openDatabase(
            context.getDatabasePath(name).path,
            "",
            null,
            CipherDatabase.OPEN_READONLY,
            null,
          )
          .use {
            it.rawQuery("SELECT COUNT(*) FROM transactions", emptyArray()).use { cursor ->
              cursor.moveToFirst()
              cursor.getInt(0)
            }
          }
      }

    assertTrue("空口令不应能读出数据，实际：$openedAsPlaintext", openedAsPlaintext.isFailure)
  }

  @Test
  fun the_wrong_passphrase_cannot_read_the_migrated_database() {
    val name = newDatabaseName()
    createPlaintextDatabase(name, transactions = 2, categories = 1)
    DatabaseEncryption.migrateIfNeeded(context, name, passphrase)

    val attempt = runCatching { countRows(context.getDatabasePath(name), "wrong-passphrase") }

    assertTrue("错误口令必须读不出数据", attempt.isFailure)
  }

  @Test
  fun migration_leaves_no_temporary_or_backup_files_behind() {
    val name = newDatabaseName()
    createPlaintextDatabase(name, transactions = 1, categories = 1)

    DatabaseEncryption.migrateIfNeeded(context, name, passphrase)

    val file = context.getDatabasePath(name)
    assertFalse("临时文件应被清理", File(file.path + ".encrypted.tmp").exists())
    assertFalse("回滚副本应被清理", File(file.path + ".plaintext.bak").exists())
  }

  @Test
  fun migration_preserves_the_room_schema_version() {
    // 这条是补上的回归测试，对应一次真实事故：
    // sqlcipher_export 只复制表与数据，**不复制 `PRAGMA user_version`**。少了它，
    // 加密后的库版本号是 0，Room 认为这是全新数据库 → 重新建表 + **再跑一遍种子回调**
    // → 每个内置分类都变成两份（28 个分类变成 56 个），而账目数据完好，
    // 所以从表面上完全看不出问题，只有去数分类才会发现。
    val name = newDatabaseName()
    createPlaintextDatabase(name, transactions = 3, categories = 28, schemaVersion = 3)

    DatabaseEncryption.migrateIfNeeded(context, name, passphrase)

    val file = context.getDatabasePath(name)
    assertEquals("schema 版本必须原样带过去", 3, userVersionOf(file, passphrase))
    assertEquals("分类数不能翻倍", 3 to 28, countRows(file, passphrase))
  }

  @Test
  fun migration_carries_over_a_different_schema_version_too() {
    // 不能把 3 写死：升级路径上源库可能是任意版本，迁移只负责搬运
    val name = newDatabaseName()
    createPlaintextDatabase(name, transactions = 1, categories = 2, schemaVersion = 2)

    DatabaseEncryption.migrateIfNeeded(context, name, passphrase)

    assertEquals(2, userVersionOf(context.getDatabasePath(name), passphrase))
  }

  // ---------- 幂等与空路径 ----------

  @Test
  fun running_migration_twice_is_a_no_op() {
    val name = newDatabaseName()
    createPlaintextDatabase(name, transactions = 4, categories = 2)
    DatabaseEncryption.migrateIfNeeded(context, name, passphrase)

    val second = DatabaseEncryption.migrateIfNeeded(context, name, passphrase)

    assertEquals(EncryptionMigrationResult.AlreadyEncrypted, second)
    assertEquals("二次调用不能损坏数据", 4 to 2, countRows(context.getDatabasePath(name), passphrase))
  }

  @Test
  fun a_fresh_install_reports_no_database() {
    val result = DatabaseEncryption.migrateIfNeeded(context, newDatabaseName(), passphrase)

    assertEquals(EncryptionMigrationResult.NoDatabase, result)
  }

  // ---------- 失败必须不丢数据 ----------

  @Test
  fun a_failed_migration_leaves_the_original_database_intact() {
    // 让导出目标路径被一个**非空目录**占住：
    // 迁移开头会尝试删掉这个临时路径，但非空目录删不掉，于是 SQLCipher 无法把
    // 加密副本写到那里，迁移必然失败。此时原库必须原封不动、仍然可读。
    val name = newDatabaseName()
    createPlaintextDatabase(name, transactions = 5, categories = 2)
    val file = context.getDatabasePath(name)
    val blocker = File(file.path + ".encrypted.tmp")
    assertTrue("前提：占位目录要建得出来", blocker.mkdirs())
    File(blocker, "keep").writeText("占用，让目录无法被删除")

    val result = DatabaseEncryption.migrateIfNeeded(context, name, passphrase)

    assertTrue("这次迁移应当失败，实际：$result", result is EncryptionMigrationResult.Failed)
    assertTrue("原库必须还在", file.exists())
    assertTrue("原库必须仍然是明文（说明没有被半途改成加密库）", DatabaseEncryption.isPlaintext(file))

    // 用明文方式打开，数据必须完整
    val rows =
      FrameworkDatabase.openDatabase(file.path, null, FrameworkDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM transactions", null).use { cursor ->
          cursor.moveToFirst()
          cursor.getInt(0)
        }
      }
    assertEquals("回滚之后数据必须一笔不少", 5, rows)

    File(blocker, "keep").delete()
    blocker.delete()
  }
}

/**
 * 数据库口令的解析。
 *
 * 这里最关键的一条是「口令解不开且库还在」的判断——它是整个加密方案里
 * **唯一会造成数据永久丢失的决策点**，必须确认它不会静默重置。
 */
@RunWith(AndroidJUnit4::class)
class DatabasePassphraseTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
  }

  @After
  fun tearDown() {
    DatabasePassphrase.resetForTesting(context)
  }

  private fun storedBlob(): String? =
    context
      .getSharedPreferences("moneytracker.secure", Context.MODE_PRIVATE)
      .getString("db_passphrase_v1", null)

  private fun overwriteBlob(value: String) {
    context
      .getSharedPreferences("moneytracker.secure", Context.MODE_PRIVATE)
      .edit()
      .putString("db_passphrase_v1", value)
      .commit()
  }

  @Test
  fun the_passphrase_is_created_once_and_then_reused() {
    val first = DatabasePassphrase.resolve(context, databaseExists = false)
    val second = DatabasePassphrase.resolve(context, databaseExists = false)

    assertTrue(first is PassphraseResult.Ready)
    assertEquals(
      "同一次安装里口令必须稳定，否则数据库会打不开",
      (first as PassphraseResult.Ready).passphrase,
      (second as PassphraseResult.Ready).passphrase,
    )
  }

  @Test
  fun the_passphrase_is_not_stored_in_plaintext() {
    val result = DatabasePassphrase.resolve(context, databaseExists = false) as PassphraseResult.Ready

    val blob = storedBlob()
    assertNotNull("口令必须被保存（否则库打不开）", blob)
    assertFalse("保存的必须是密文，不能出现口令本身", blob!!.contains(result.passphrase))
    assertTrue("口令应当够长", result.passphrase.length >= 32)
  }

  @Test
  fun an_unreadable_passphrase_is_regenerated_when_there_is_no_database() {
    // 没有数据库时旧口令没有被任何数据依赖，重建是安全的
    DatabasePassphrase.resolve(context, databaseExists = false)
    overwriteBlob("v1:这不是合法的 base64:也不是")

    val result = DatabasePassphrase.resolve(context, databaseExists = false)

    assertTrue("应当重新生成而不是报错", result is PassphraseResult.Ready)
  }

  @Test
  fun an_unreadable_passphrase_is_fatal_when_a_database_exists() {
    // 这是全项目最该失败得响亮的一处：如果这里静默换一个新口令，
    // 数据库会以新口令重建，用户看到的是一个空账本——和「数据被删了」没有区别，
    // 而旧数据其实还躺在磁盘上，只是再也解不开。
    DatabasePassphrase.resolve(context, databaseExists = false)
    val blobBefore = storedBlob()
    overwriteBlob("v1:这不是合法的 base64:也不是")

    val result = DatabasePassphrase.resolve(context, databaseExists = true)

    assertEquals(PassphraseResult.Unrecoverable, result)
  }

  @Test
  fun every_passphrase_is_different() {
    // 口令必须是随机的，不能是固定值或可预测的推导结果
    val seen = mutableSetOf<String>()
    repeat(5) {
      DatabasePassphrase.resetForTesting(context)
      seen.add((DatabasePassphrase.resolve(context, false) as PassphraseResult.Ready).passphrase)
    }

    assertEquals("每次生成的口令都应不同", 5, seen.size)
  }
}
