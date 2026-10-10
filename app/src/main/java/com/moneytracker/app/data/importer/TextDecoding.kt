package com.moneytracker.app.data.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 账单文件的文本解码。
 *
 * 为什么需要它：**微信导出的 CSV 是 UTF-8（常带 BOM），而支付宝导出的是 GBK**。
 * 一律按 UTF-8 解会把支付宝账单的中文全变成乱码，整个导入功能直接失效。
 */
object TextDecoding {

  private val GBK: Charset? =
    runCatching { Charset.forName("GBK") }.getOrNull()

  private const val UTF8_BOM_FIRST = 0xEF.toByte()
  private const val UTF8_BOM_SECOND = 0xBB.toByte()
  private const val UTF8_BOM_THIRD = 0xBF.toByte()

  fun hasUtf8Bom(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
      bytes[0] == UTF8_BOM_FIRST &&
      bytes[1] == UTF8_BOM_SECOND &&
      bytes[2] == UTF8_BOM_THIRD

  /**
   * 解码为文本。
   *
   * 策略：
   *  1. 有 UTF-8 BOM → 剥掉 BOM 后按 UTF-8 解（微信账单常见）
   *  2. 严格按 UTF-8 解，成功则用 UTF-8
   *  3. 失败则回落 GBK（支付宝账单常见）
   *
   * 用**严格**解码（遇到非法字节就报错）来判定，而不是「宽松解码看有没有替换字符」：
   * 后者对短文件容易误判。GBK 中文的字节序列绝大多数不是合法 UTF-8，因此这个判据很可靠；
   * 纯 ASCII 文件两种编码结果相同，判错也无影响。
   */
  fun decode(bytes: ByteArray): String {
    if (hasUtf8Bom(bytes)) {
      return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    if (isValidUtf8(bytes)) {
      return String(bytes, Charsets.UTF_8)
    }
    return GBK?.let { String(bytes, it) } ?: String(bytes, Charsets.ISO_8859_1)
  }

  fun isGbkAvailable(): Boolean = GBK != null

  private fun isValidUtf8(bytes: ByteArray): Boolean {
    val decoder =
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
      decoder.decode(ByteBuffer.wrap(bytes))
      true
    } catch (e: CharacterCodingException) {
      false
    }
  }
}
