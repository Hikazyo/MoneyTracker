package com.moneytracker.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 口令存在但解不开，而数据库还在——数据无法恢复。 */
class DatabaseUnreadableException(cause: Throwable?) :
  Exception("数据库口令无法解开，账本数据已无法读取", cause)

/** 口令的解析结果。 */
sealed interface PassphraseResult {

  data class Ready(val passphrase: String) : PassphraseResult

  /**
   * 口令存在但解不开，且数据库文件还在。
   *
   * 这种情况**绝不能**静默生成新口令：那样数据库会以新口令重建，
   * 用户看到的是一个空账本——看起来和「数据被删了」一模一样，
   * 而且旧数据其实还在磁盘上，只是再也解不开了。
   */
  data object Unrecoverable : PassphraseResult
}

/**
 * 数据库口令的生成与保管。
 *
 * ## 做法
 *
 * 口令本身就是随机 32 字节（以十六进制字符串表示），它并不直接存在设备上，
 * 而是用一把 **Android Keystore 里的 AES 密钥**加密后存进 SharedPreferences。
 * Keystore 的密钥材料不出硬件/系统，应用只能请求它做加解密，拿不到密钥本身。
 *
 * 用十六进制表示口令不是为了好看：SQLCipher 迁移时要把口令嵌进 `ATTACH ... KEY '...'`，
 * 而 `ATTACH` 不支持绑定参数。十六进制只含 `0-9a-f`，不可能出现引号，
 * 于是从根本上消除了拼接 SQL 的注入面（口令若是任意字节就可能含 `'`）。
 *
 * ## 它能挡住什么 —— 这一节必须诚实
 *
 * | 攻击场景 | 挡得住吗 |
 * |---|---|
 * | 单独拿走数据库文件（取证镜像、`adb backup`、拷走文件） | ✅ 只有密文 |
 * | 未加密的整机备份、被同步到别处的数据目录 | ✅ 只有密文 |
 * | 系统云备份 / 换机迁移 | ✅ 已在清单里显式排除 |
 * | 设备已解锁、且能在本应用进程里执行代码（root 后直接调本应用） | ❌ 它可以直接请求 Keystore 解密 |
 *
 * 也就是说，它把「拿到文件就能读」提升为「拿到已解锁的设备才能读」。
 * 要连后者也挡住，需要口令由用户输入（不落盘）+ 生物识别，那是「应用锁」，
 * 属于另一个功能，本轮没有采用。
 */
object DatabasePassphrase {

  private const val PREFS_NAME = "moneytracker.secure"
  private const val PREF_KEY = "db_passphrase_v1"

  private const val ANDROID_KEYSTORE = "AndroidKeyStore"
  private const val WRAPPING_KEY_ALIAS = "moneytracker.db.wrapping"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val GCM_TAG_BITS = 128
  private const val GCM_IV_BYTES = 12
  private const val ENVELOPE_PREFIX = "v1"

  /** 32 字节随机口令。 */
  private const val PASSPHRASE_BYTES = 32

  @Volatile private var cachedWrappingKey: SecretKey? = null

  /**
   * 取得数据库口令，必要时新建。
   *
   * @param databaseExists 数据库文件是否已存在。这个参数决定「口令解不开」时是致命错误
   *   还是可以重新生成——**这是整个加密方案里唯一会造成数据永久丢失的判断点**，
   *   所以由调用方显式传入，而不是在这里猜。
   */
  fun resolve(context: Context, databaseExists: Boolean): PassphraseResult {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val stored = prefs.getString(PREF_KEY, null)

    if (stored != null) {
      return try {
        PassphraseResult.Ready(unwrap(stored))
      } catch (e: Exception) {
        if (databaseExists) {
          PassphraseResult.Unrecoverable
        } else {
          // 数据库还不存在，所以旧口令没有被任何数据依赖，重建是安全的
          val regenerated = randomPassphrase()
          prefs.edit().putString(PREF_KEY, wrap(regenerated)).commit()
          PassphraseResult.Ready(regenerated)
        }
      }
    }

    val generated = randomPassphrase()
    // 用 commit 而不是 apply：紧接着就要拿它建库，必须已经落盘，
    // 否则进程在这之间被杀掉就会得到一个「有库无口令」的不可读状态
    prefs.edit().putString(PREF_KEY, wrap(generated)).commit()
    return PassphraseResult.Ready(generated)
  }

  /** 供测试使用：清掉已保存的口令与包装密钥。 */
  fun resetForTesting(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    cachedWrappingKey = null
    runCatching {
      KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(WRAPPING_KEY_ALIAS)
    }
  }

  // ---------- 口令的包装与解包 ----------

  private fun randomPassphrase(): String {
    val bytes = ByteArray(PASSPHRASE_BYTES)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
  }

  /** 格式：`v1:<iv 的 base64>:<密文的 base64>` */
  private fun wrap(passphrase: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
    val cipherText = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
    return listOf(
        ENVELOPE_PREFIX,
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        Base64.encodeToString(cipherText, Base64.NO_WRAP),
      )
      .joinToString(":")
  }

  private fun unwrap(envelope: String): String {
    val parts = envelope.split(":")
    require(parts.size == 3 && parts[0] == ENVELOPE_PREFIX) { "口令信封格式不正确" }

    val iv = Base64.decode(parts[1], Base64.NO_WRAP)
    val cipherText = Base64.decode(parts[2], Base64.NO_WRAP)

    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
    return String(cipher.doFinal(cipherText), Charsets.UTF_8)
  }

  /**
   * 取 Keystore 里的包装密钥，没有就建一把。
   *
   * 刻意**不**设置 `setUserAuthenticationRequired(true)`：那会要求每次开库都过一遍
   * 生物识别/锁屏，等于把「应用锁」强加给所有用户。本轮没有采用应用锁，
   * 因此这里只依赖设备级加密与 Keystore 本身。
   */
  private fun wrappingKey(): SecretKey {
    cachedWrappingKey?.let { return it }

    synchronized(this) {
      cachedWrappingKey?.let { return it }

      val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
      (keyStore.getEntry(WRAPPING_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
        cachedWrappingKey = it.secretKey
        return it.secretKey
      }

      val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
      generator.init(
        KeyGenParameterSpec.Builder(
            WRAPPING_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
          )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          // 不用随机化 IV 之外的额外措施：GCM 每次加密自动生成随机 IV，我们把它随密文一起存
          .build()
      )
      val key = generator.generateKey()
      cachedWrappingKey = key
      return key
    }
  }
}
