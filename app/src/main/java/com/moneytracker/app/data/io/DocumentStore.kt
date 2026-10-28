package com.moneytracker.app.data.io

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文档读写抽象。
 *
 * 用 **String** 作为文档标识（即 SAF 返回的 Uri 字符串），而不是直接依赖 `android.net.Uri`：
 * Uri 是 Android 框架类，在纯 JVM 单元测试里既无法构造也无法 mock（项目未引入 Mockito），
 * 直接依赖它会让 SettingsViewModel 完全不可测。字符串句柄把 Android 依赖收敛在本文件内。
 */
interface DocumentStore {

  suspend fun writeText(documentId: String, text: String): Result<Unit>

  suspend fun readText(documentId: String): Result<String>

  /**
   * 按**原始字节**读取。
   *
   * 导入账单必须用这个而不是 [readText]：账单文件的编码不固定
   * （微信 UTF-8、支付宝 GBK），先拿到字节才能做编码嗅探。
   */
  suspend fun readBytes(documentId: String): Result<ByteArray>

  /**
   * 文件的展示名，例如 `Screenshot_20261028_153409.jpg`。
   *
   * 只有截图记账用得到：截图里往往只写「10月24日」，年份只能从文件名补。
   * 给了默认实现（返回 null），这样既有的测试替身不必全部改动。
   */
  suspend fun displayName(documentId: String): String? = null
}

/** 读到的文件超过体积上限。 */
class DocumentTooLargeException(val limitBytes: Long, val actualBytes: Long) :
  IOException("文件过大：${actualBytes / 1024 / 1024} MB，上限 ${limitBytes / 1024 / 1024} MB")

/** 基于 ContentResolver 的实现：配合 SAF，无需申请任何存储权限。 */
class AndroidDocumentStore(private val context: Context) : DocumentStore {

  override suspend fun writeText(documentId: String, text: String): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        // 只接受 content:// 。当前没有外部 Intent 入口，这条挡不住任何现实攻击，
        // 属于纵深防御：万一将来加了「用其它应用打开备份」之类的功能，
        // file:// / http:// 不会被悄悄读进来。
        requireScheme(documentId)

        // 模式必须带 "t"（truncate）。少了它，覆盖导出到已有文件时会残留上一次的尾部内容，
        // 生成一个语法上就已经坏掉的 CSV。
        val stream =
          context.contentResolver.openOutputStream(Uri.parse(documentId), "wt")
            ?: throw IOException("无法打开输出流")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
      }
    }

  override suspend fun readText(documentId: String): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        requireScheme(documentId)
        val stream =
          context.contentResolver.openInputStream(Uri.parse(documentId))
            ?: throw IOException("无法打开输入流")
        stream.use { String(readBounded(it, MAX_DOCUMENT_BYTES), Charsets.UTF_8) }
      }
    }

  override suspend fun readBytes(documentId: String): Result<ByteArray> =
    withContext(Dispatchers.IO) {
      runCatching {
        requireScheme(documentId)
        val stream =
          context.contentResolver.openInputStream(Uri.parse(documentId))
            ?: throw IOException("无法打开输入流")
        stream.use { readBounded(it, MAX_DOCUMENT_BYTES) }
      }
    }

  override suspend fun displayName(documentId: String): String? =
    withContext(Dispatchers.IO) {
      runCatching {
        requireScheme(documentId)
        context.contentResolver
          .query(
            Uri.parse(documentId),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
          )
          ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
      }.getOrNull()
    }

  private fun requireScheme(documentId: String) {
    require(Uri.parse(documentId).scheme == "content") { "只接受系统文件选择器返回的文件" }
  }

  companion object {
    /**
     * 单个文件的大小上限：32 MB。
     *
     * 为什么必须有：`readBytes()` 会把整个文件读进内存，而账单/备份文件来自用户选择，
     * 大小完全不受控。没有上限时，一个几百 MB 的文件会让应用直接 OOM 崩溃，
     * 而 OOM 是 `Error` 不是 `Exception`，外层 `catch (Exception)` 兜不住。
     *
     * 32 MB 的选择依据：真实的微信/支付宝年度账单在 1 MB 上下，
     * 即便含全部明细也远小于这个数；而 32 MB 的字节数组在任何现代设备上都能安全分配。
     */
    const val MAX_DOCUMENT_BYTES: Long = 32L * 1024 * 1024

    /**
     * 读取时**边读边计数**，超过上限立即中止。
     *
     * 不能先看 `available()` 或文件长度再决定：那两者对 ContentProvider 都不一定准确，
     * 而且「压缩炸弹」式的数据流在读取过程中才会膨胀。
     */
    private fun readBounded(stream: InputStream, limit: Long): ByteArray {
      val buffer = ByteArray(64 * 1024)
      val out = ByteArrayOutputStream()
      var total = 0L
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw DocumentTooLargeException(limit, total)
        out.write(buffer, 0, read)
      }
      return out.toByteArray()
    }
  }
}
