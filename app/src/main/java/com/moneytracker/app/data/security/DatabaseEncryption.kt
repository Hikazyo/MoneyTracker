package com.moneytracker.app.data.security

import android.content.Context
import android.database.sqlite.SQLiteDatabase as FrameworkDatabase
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

/** 迁移结果，用于让调用方（以及日志）知道发生了什么。 */
sealed interface EncryptionMigrationResult {

  /** 数据库本来就已经加密，什么都没做。 */
  data object AlreadyEncrypted : EncryptionMigrationResult

  /** 全新安装，还没有数据库文件。 */
  data object NoDatabase : EncryptionMigrationResult

  /** 明文库已成功转换为加密库。 */
  data class Migrated(val transactionRows: Int, val categoryRows: Int) : EncryptionMigrationResult

  /** 迁移失败，**原库已恢复原状**，数据没有丢。 */
  data class Failed(val reason: String) : EncryptionMigrationResult
}

/**
 * 把已有的**明文**数据库就地转换成 SQLCipher 加密库。
 *
 * ## 为什么需要它
 *
 * v1.2 及更早的版本把账本以明文 SQLite 存放。v1.3 起启用加密，
 * 但老用户的库里已经有数据，**不能重建成空库**——那等于把用户的账本删了。
 * 所以必须在首次以加密方式打开之前，把明文文件转换过去。
 *
 * ## 转换流程（每一步都为「不丢数据」服务）
 *
 * 1. 用**框架自带的** SQLite 开关一次库，让 SQLite 把 `-wal` 里未落盘的内容合并回主库。
 *    这一步用框架驱动而不是 SQLCipher，是因为这个库是框架创建的，
 *    用同一个实现去收尾最稳妥。
 * 2. 用 SQLCipher 以**空口令**打开明文库（SQLCipher 支持明文模式），
 *    把加密副本 `EXPORT` 到一个**新文件**，全程不碰原文件。
 * 3. **验证副本**：用新口令打开副本，比对分类与账目的行数。行数不一致就不换。
 * 4. 才做替换：原文件改名为 `.plaintext.bak`，副本改名为正式文件。
 * 5. 用新口令打开正式文件再验证一次；成功才删掉 `.bak`，失败则把 `.bak` 改回来。
 *
 * 关键点是**任何时刻磁盘上都至少有一份完整可读的数据**：副本写完并验证通过之前，
 * 原文件一动不动；替换之后如果验证失败，`.bak` 还在，可以整体回滚。
 */
object DatabaseEncryption {

  private const val CIPHER_LIBRARY = "sqlcipher"

  /** 明文 SQLite 的文件头，正好 16 字节。 */
  private const val PLAINTEXT_HEADER = "SQLite format 3\u0000"
  private const val HEADER_BYTES = 16

  /** 迁移时用的临时文件名后缀。 */
  private const val TEMP_SUFFIX = ".encrypted.tmp"
  private const val BACKUP_SUFFIX = ".plaintext.bak"

  /** 加载 SQLCipher 原生库。重复调用是安全的。 */
  fun loadLibrary() {
    System.loadLibrary(CIPHER_LIBRARY)
  }

  /**
   * 文件是不是**明文** SQLite。
   *
   * 判据是文件头：明文 SQLite 固定以 `SQLite format 3\0` 开头；
   * 加密库的头部是随机盐，不可能与之相同。空文件按明文处理（会被当作空库）。
   */
  fun isPlaintext(file: File): Boolean {
    if (!file.exists() || file.length() < HEADER_BYTES) return true
    val header = ByteArray(HEADER_BYTES)
    file.inputStream().use { stream ->
      var read = 0
      while (read < HEADER_BYTES) {
        val n = stream.read(header, read, HEADER_BYTES - read)
        if (n < 0) break
        read += n
      }
      if (read < HEADER_BYTES) return true
    }
    return String(header, Charsets.US_ASCII) == PLAINTEXT_HEADER
  }

  /** 数据库文件旁边 SQLite 生成的附属文件。 */
  private fun sidecarFiles(dbFile: File): List<File> =
    listOf(File(dbFile.path + "-wal"), File(dbFile.path + "-shm"), File(dbFile.path + "-journal"))

  fun migrateIfNeeded(
    context: Context,
    databaseName: String,
    passphrase: String,
  ): EncryptionMigrationResult {
    val dbFile = context.getDatabasePath(databaseName)
    if (!dbFile.exists()) return EncryptionMigrationResult.NoDatabase
    if (!isPlaintext(dbFile)) return EncryptionMigrationResult.AlreadyEncrypted

    loadLibrary()

    return try {
      migrate(dbFile, passphrase)
    } catch (e: Throwable) {
      // 迁移失败必须是「什么都没发生」，而不是半个状态
      rollbackIfNeeded(dbFile)
      EncryptionMigrationResult.Failed(e.message ?: e.javaClass.simpleName)
    }
  }

  private fun migrate(dbFile: File, passphrase: String): EncryptionMigrationResult {
    // 1) 先把 WAL 合并回主库
    FrameworkDatabase.openDatabase(dbFile.path, null, FrameworkDatabase.OPEN_READWRITE).close()

    val temp = File(dbFile.path + TEMP_SUFFIX)
    temp.delete()

    // 2) 明文打开 → 导出加密副本。
    //
    // **必须用「完全不设口令」的入口**（openOrCreateDatabase），而不是传空字符串。
    // 传 "" 时 SQLCipher 会为这条连接装配一个空口令的编解码器：读明文库看起来正常，
    // 但随后 `ATTACH ... KEY` 会以 SQLITE_CANTOPEN(14) 失败，且错误信息完全指不到原因。
    // 这是实测出来的（见 DEV_NOTES 中关于本次加密改造的记录）。
    val source: CipherDatabase = CipherDatabase.openOrCreateDatabase(dbFile, null)

    // 源库的 schema 版本。Room 靠它判断「这是已有库还是新库」，迁移时必须原样带过去。
    val schemaVersion = readUserVersion(source)

    val counts =
      try {
        val before = countRows(source)
        // ATTACH 不支持绑定参数，只能拼接。口令是十六进制串（见 DatabasePassphrase），
        // 路径是本应用私有目录，两者都不可能含引号；这里仍显式转义一次作为纵深防御。
        //
        // 用 execSQL 而不是 rawExecSQL：后者不抛错，SQL 失败时只会静静地什么都不做——
        // 表现就是「加密副本没生成」这种毫无线索的结果。
        source.execSQL(
          "ATTACH DATABASE '${escapeSqlLiteral(temp.path)}' AS encrypted KEY " +
            "'${escapeSqlLiteral(passphrase)}'"
        )
        // sqlcipher_export 是个返回结果集的函数，必须真的执行到取行那一步；
        // 只 prepare 不取行的话，导出不会发生。
        source.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray()).use { it.moveToFirst() }

        // **必须手动补上 user_version。**
        //
        // sqlcipher_export 只复制表结构与数据，**不复制 `PRAGMA user_version`**——它是数据库
        // 头部的字段，不是表。少了这一步，导出的加密库版本号是 0，Room 会认为这是一个全新的
        // 数据库，于是重新建表并**再跑一遍种子回调**：结果是每个内置分类都出现两份，
        // 用户在分类管理里看到 56 个分类，其中一半是重复的。
        //
        // 这个坑是实测发现的：首次真机升级验证导出的备份里赫然是 56 个分类。
        // RoomOpenHelper 的判定逻辑是 `isMigrationRequired(0, version) == false` → 走「新建」
        // 分支 → 调用 onCreate 回调，建表用 IF NOT EXISTS 所以不报错，重复数据却已经写进去了。
        source.execSQL("PRAGMA encrypted.user_version = $schemaVersion")

        source.execSQL("DETACH DATABASE encrypted")
        before
      } finally {
        source.close()
      }

    // 3) 验证副本确实能用新口令打开、且行数对得上
    if (!temp.exists() || temp.length() == 0L) {
      temp.delete()
      throw IllegalStateException("加密副本没有生成")
    }

    val verified =
      CipherDatabase.openDatabase(
          temp.path,
          passphrase,
          null,
          CipherDatabase.OPEN_READONLY,
          null,
        )
        .use { countRows(it) to readUserVersion(it) }

    if (verified.first != counts) {
      temp.delete()
      throw IllegalStateException(
        "加密副本校验失败：原库 ${counts.transactions} 笔账目 / ${counts.categories} 个分类，" +
          "副本 ${verified.first.transactions} 笔 / ${verified.first.categories} 个"
      )
    }
    // 与行数同样重要：版本号没带过去的话，Room 会把已有数据当成新库、再跑一遍种子回调，
    // 结果是内置分类凭空翻倍——数据没丢，但全错了，而且不会有任何报错。
    if (verified.second != schemaVersion) {
      temp.delete()
      throw IllegalStateException(
        "加密副本的 schema 版本不对：原库 $schemaVersion，副本 ${verified.second}"
      )
    }

    // 4) 替换。先把原文件改名留作回滚点，再让副本就位。
    val backup = File(dbFile.path + BACKUP_SUFFIX)
    backup.delete()
    if (!dbFile.renameTo(backup)) throw IllegalStateException("无法备份原数据库文件")
    if (!temp.renameTo(dbFile)) {
      // 副本没能就位，立刻把原文件放回去
      backup.renameTo(dbFile)
      throw IllegalStateException("无法让加密数据库就位")
    }

    // 5) 用正式路径再验证一次，成功才清理回滚点与旧的附属文件
    try {
      CipherDatabase.openDatabase(
          dbFile.path,
          passphrase,
          null,
          CipherDatabase.OPEN_READONLY,
          null,
        )
        .use { countRows(it) }
    } catch (e: Throwable) {
      dbFile.delete()
      backup.renameTo(dbFile)
      throw IllegalStateException("加密数据库未能通过最终校验，已回滚")
    }

    backup.delete()
    sidecarFiles(dbFile).forEach { it.delete() }

    return EncryptionMigrationResult.Migrated(
      transactionRows = counts.transactions,
      categoryRows = counts.categories,
    )
  }

  /** 迁移过程中抛异常时的兜底：把 `.bak` 放回原位，保证用户至少还有原库。 */
  private fun rollbackIfNeeded(dbFile: File) {
    val backup = File(dbFile.path + BACKUP_SUFFIX)
    if (backup.exists() && !dbFile.exists()) {
      backup.renameTo(dbFile)
    }
    File(dbFile.path + TEMP_SUFFIX).delete()
  }

  private class RowCounts(val transactions: Int, val categories: Int) {
    override fun equals(other: Any?): Boolean =
      other is RowCounts &&
        other.transactions == transactions &&
        other.categories == categories

    override fun hashCode(): Int = transactions * 31 + categories
  }

  private fun countRows(db: CipherDatabase): RowCounts =
    RowCounts(
      transactions = db.rawQuery("SELECT COUNT(*) FROM transactions", emptyArray()).use { it.readInt() },
      categories = db.rawQuery("SELECT COUNT(*) FROM categories", emptyArray()).use { it.readInt() },
    )

  /** 读 `PRAGMA user_version`，也就是 Room 的 schema 版本号。 */
  private fun readUserVersion(db: CipherDatabase): Int =
    db.rawQuery("PRAGMA user_version", emptyArray()).use { it.readInt() }

  private fun android.database.Cursor.readInt(): Int {
    moveToFirst()
    return getInt(0)
  }

  private fun escapeSqlLiteral(raw: String): String = raw.replace("'", "''")
}
