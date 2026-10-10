package com.moneytracker.app.data.io

import android.content.Context
import android.net.Uri
import java.io.IOException
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
}

/** 基于 ContentResolver 的实现：配合 SAF，无需申请任何存储权限。 */
class AndroidDocumentStore(private val context: Context) : DocumentStore {

  override suspend fun writeText(documentId: String, text: String): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
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
        val stream =
          context.contentResolver.openInputStream(Uri.parse(documentId))
            ?: throw IOException("无法打开输入流")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
      }
    }

  override suspend fun readBytes(documentId: String): Result<ByteArray> =
    withContext(Dispatchers.IO) {
      runCatching {
        val stream =
          context.contentResolver.openInputStream(Uri.parse(documentId))
            ?: throw IOException("无法打开输入流")
        stream.use { it.readBytes() }
      }
    }
}
