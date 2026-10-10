package com.moneytracker.app.data.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 备份文件的编解码、可选口令加密与版本校验。
 *
 * 恢复是破坏性操作（会覆盖现有全部数据），所以解析失败与版本不符都必须**明确拒绝**，
 * 绝不能「尽力而为」地写一半进去。
 *
 * ## 为什么要给备份加密
 *
 * 数据库本身用 SQLCipher 加密了，但「创建备份」导出的是一个**完整的明文副本**，
 * 而它的落点由用户在系统文件选择器里指定——常见就是 Download 目录或某个网盘同步目录。
 * 一份明文备份放在那里，等于把加密数据库的防护整个绕过去了。
 *
 * ## 加密方案
 *
 * - **口令派生**：PBKDF2。算法名写进文件头，将来换算法也认得出旧文件。
 *   选 `PBKDF2WithHmacSHA1` 而不是 SHA256 变体，是因为后者要 API 26+，
 *   而本项目 minSdk 是 24。在旧设备上备份的文件必须能在新设备上打开、反之亦然，
 *   所以全平台只能用同一个算法。PBKDF2-HMAC-SHA1 在**足够迭代次数**下仍是合适的 KDF。
 * - **加密**：AES-256-GCM。GCM 自带完整性校验：口令错误或文件被改动都会在解密时报错，
 *   不会解出一段垃圾数据再拿去覆盖账本。
 * - **随机数**：每次备份都新生成 salt 与 IV，随密文一起存在文件里（它们不是秘密）。
 * - **Base64 用 Kotlin 标准库的实现**，不用 `android.util.Base64`——后者是 Android 框架类，
 *   在纯 JVM 单元测试里无法调用，会让这个类的加密逻辑完全测不到。
 *
 * 口令**不保存在任何地方**。忘记口令 = 这份备份打不开，这是加密的固有代价，
 * 因此界面上必须写清楚。
 */
object BackupCodec {

  /** 当前备份格式版本。改动 [BackupFile] 结构时必须递增。 */
  const val CURRENT_SCHEMA_VERSION = 1

  /** 加密备份信封的标识，用来和明文备份区分。 */
  const val ENCRYPTED_FORMAT = "moneytracker-encrypted-backup"

  private const val ENVELOPE_VERSION = 1
  private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA1"
  private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"

  /**
   * PBKDF2 迭代次数。
   *
   * 在「抗暴力破解」与「手机上不卡」之间取平衡：20 万次在当代手机上约 100–300 ms，
   * 而用户只在创建/恢复备份时各等一次。
   */
  private const val KDF_ITERATIONS = 200_000

  private const val SALT_BYTES = 16
  private const val IV_BYTES = 12
  private const val KEY_BITS = 256
  private const val GCM_TAG_BITS = 128

  private val json =
    Json {
      prettyPrint = true
      // 容忍未来新增的字段：旧版本 App 至少能读出自己认识的部分
      ignoreUnknownKeys = true
      encodeDefaults = true
    }

  /** 不加密（明文）编码，用于「用户明确选择不设口令」的情况。 */
  fun encode(backup: BackupFile): String = json.encodeToString(backup)

  /** 加密编码。口令为空时等同于 [encode]。 */
  fun encode(backup: BackupFile, password: String?): String {
    if (password.isNullOrEmpty()) return encode(backup)
    return encrypt(json.encodeToString(backup), password)
  }

  @OptIn(ExperimentalEncodingApi::class)
  fun encrypt(plainText: String, password: String): String {
    val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
    val key = deriveKey(password, salt)

    val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

    val envelope =
      JsonObject(
        mapOf(
          "format" to JsonPrimitive(ENCRYPTED_FORMAT),
          "version" to JsonPrimitive(ENVELOPE_VERSION),
          "kdf" to JsonPrimitive(KDF_ALGORITHM),
          "iterations" to JsonPrimitive(KDF_ITERATIONS),
          "cipher" to JsonPrimitive(CIPHER_ALGORITHM),
          "salt" to JsonPrimitive(Base64.encode(salt)),
          "iv" to JsonPrimitive(Base64.encode(cipher.iv)),
          "payload" to JsonPrimitive(Base64.encode(cipherText)),
        )
      )
    return json.encodeToString(JsonObject.serializer(), envelope)
  }

  /** 这份文本是不是加密备份。恢复时据此决定要不要向用户要口令。 */
  fun isEncrypted(text: String): Boolean =
    runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return false
        root["format"]?.jsonPrimitive?.content == ENCRYPTED_FORMAT
      }
      .getOrDefault(false)

  sealed interface DecodeResult {
    data class Ok(val backup: BackupFile) : DecodeResult

    /** 版本不兼容。宁可拒绝，也不要写出一份坏数据。 */
    data class UnsupportedVersion(val found: Int, val expected: Int) : DecodeResult

    data class Malformed(val message: String) : DecodeResult

    /** 这份备份是加密的，需要口令才能继续。 */
    data object PasswordRequired : DecodeResult

    /** 给了口令但解不开：口令错，或文件被改动过。 */
    data object WrongPassword : DecodeResult
  }

  /**
   * 解码。
   *
   * @param password 加密备份所需的口令；明文备份会忽略它
   */
  fun decode(text: String, password: String? = null): DecodeResult {
    val payload =
      if (isEncrypted(text)) {
        if (password.isNullOrEmpty()) return DecodeResult.PasswordRequired
        when (val decrypted = decrypt(text, password)) {
          is DecryptResult.Ok -> decrypted.plainText
          DecryptResult.WrongPassword -> return DecodeResult.WrongPassword
        }
      } else {
        text
      }

    val parsed =
      try {
        json.decodeFromString<BackupFile>(payload)
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

  private sealed interface DecryptResult {
    data class Ok(val plainText: String) : DecryptResult

    data object WrongPassword : DecryptResult
  }

  @OptIn(ExperimentalEncodingApi::class)
  private fun decrypt(envelopeText: String, password: String): DecryptResult =
    try {
      val root = json.parseToJsonElement(envelopeText) as JsonObject
      val salt = Base64.decode(root.getValue("salt").jsonPrimitive.content)
      val iv = Base64.decode(root.getValue("iv").jsonPrimitive.content)
      val payload = Base64.decode(root.getValue("payload").jsonPrimitive.content)
      val iterations = root["iterations"]?.jsonPrimitive?.content?.toIntOrNull() ?: KDF_ITERATIONS

      val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
      cipher.init(
        Cipher.DECRYPT_MODE,
        deriveKey(password, salt, iterations),
        GCMParameterSpec(GCM_TAG_BITS, iv),
      )
      DecryptResult.Ok(String(cipher.doFinal(payload), Charsets.UTF_8))
    } catch (e: Exception) {
      // GCM 认证失败会在这里抛 AEADBadTagException。
      // 刻意不区分「口令错」与「文件被改」——对用户而言该做的都是「确认口令与文件」。
      DecryptResult.WrongPassword
    }

  private fun deriveKey(
    password: String,
    salt: ByteArray,
    iterations: Int = KDF_ITERATIONS,
  ): SecretKeySpec {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
    val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
    return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
  }

  /** 供测试断言使用：加密信封里必须记录迭代次数，否则将来改参数会解不开旧文件。 */
  internal fun iterationsOf(envelopeText: String): Int? =
    runCatching {
        val root = json.parseToJsonElement(envelopeText) as JsonObject
        root["iterations"]?.jsonPrimitive?.content?.toIntOrNull()
      }
      .getOrNull()
}
