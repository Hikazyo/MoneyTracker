package com.moneytracker.app.data.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 备份文件的编解码与版本校验。
 *
 * 恢复是破坏性操作（会覆盖现有全部数据），所以解析失败与版本不符都必须**明确拒绝**，
 * 绝不能「尽力而为」地写一半进去。
 */
object BackupCodec {

  /** 当前备份格式版本。改动 [BackupFile] 结构时必须递增。 */
  const val CURRENT_SCHEMA_VERSION = 1

  private val json =
    Json {
      prettyPrint = true
      // 容忍未来新增的字段：旧版本 App 至少能读出自己认识的部分
      ignoreUnknownKeys = true
      encodeDefaults = true
    }

  fun encode(backup: BackupFile): String = json.encodeToString(backup)

  sealed interface DecodeResult {
    data class Ok(val backup: BackupFile) : DecodeResult

    /** 版本不兼容。宁可拒绝，也不要写出一份坏数据。 */
    data class UnsupportedVersion(val found: Int, val expected: Int) : DecodeResult

    data class Malformed(val message: String) : DecodeResult
  }

  fun decode(text: String): DecodeResult {
    val parsed =
      try {
        json.decodeFromString<BackupFile>(text)
      } catch (e: SerializationException) {
        return DecodeResult.Malformed(e.message ?: "备份文件格式不正确")
      } catch (e: IllegalArgumentException) {
        return DecodeResult.Malformed(e.message ?: "备份文件格式不正确")
      }

    if (parsed.schemaVersion != CURRENT_SCHEMA_VERSION) {
      return DecodeResult.UnsupportedVersion(parsed.schemaVersion, CURRENT_SCHEMA_VERSION)
    }
    return DecodeResult.Ok(parsed)
  }
}
