package com.moneytracker.app.data.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ML Kit 中文文字识别的薄封装：图片 → [OcrLine] 列表。
 *
 * 这一层刻意做得很薄，只做「调 API + 类型转换」，把判断逻辑全部留给 [ScreenshotParser]。
 * 原因有两个：
 *  - 解析规则需要大量迭代，而识别引擎调用在 JVM 单测里跑不了，薄封装才能保住可测性；
 *  - 将来若换引擎（例如换成 Tesseract），只需要重写这个类。
 *
 * 用的是**捆绑模型版**（`text-recognition-chinese`），模型随 APK 安装到设备，
 * 运行时不下载、不联网。这也是它能接受「零权限」约束的前提。
 */
class ScreenshotOcr : AutoCloseable {

  private val recognizer =
    TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

  /** 识别一张图。调用方负责在不再需要时回收 [bitmap]。 */
  suspend fun recognize(bitmap: Bitmap): List<OcrLine> =
    suspendCancellableCoroutine { continuation ->
      recognizer
        .process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { result ->
          val lines =
            result.textBlocks.flatMap { block ->
              block.lines.map { line ->
                val box = line.boundingBox
                OcrLine(
                  text = line.text,
                  left = box?.left ?: 0,
                  top = box?.top ?: 0,
                  right = box?.right ?: 0,
                  bottom = box?.bottom ?: 0,
                )
              }
            }
          continuation.resume(lines)
        }
        .addOnFailureListener { error -> continuation.resumeWithException(error) }
    }

  override fun close() {
    recognizer.close()
  }

  companion object {

    fun decode(stream: InputStream): Bitmap? = BitmapFactory.decodeStream(stream)

    /**
     * 图片内容指纹，用于去重键 `SHOT:<前16位>:<行序号>`。
     *
     * 取 16 个十六进制字符（64 bit）足够避免同一批截图里撞车，
     * 又不至于让数据库里的 `sourceId` 长得难读。
     */
    fun imageKey(bytes: ByteArray): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
      return digest.take(8).joinToString("") { "%02x".format(it) }
    }
  }
}
