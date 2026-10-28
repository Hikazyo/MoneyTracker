package com.moneytracker.app.ui.screenshots

import android.graphics.BitmapFactory
import com.moneytracker.app.data.ocr.OcrLine
import com.moneytracker.app.data.ocr.ScreenshotOcr

/** 一张截图识别后的结果：文字行 + 原图尺寸（解析要按尺寸算相对位置）。 */
data class RecognizedScreenshot(val lines: List<OcrLine>, val width: Int, val height: Int)

/**
 * 识别引擎抽象。
 *
 * ViewModel 只依赖这个接口，不依赖 ML Kit，也不依赖 `Bitmap`——因此整个页面逻辑
 * 可以在 JVM 单测里用 fixture 驱动，不需要设备。真正的 ML Kit 调用在
 * [MlKitScreenshotRecognizer] 里，是整个功能中唯一无法离线测试的一层。
 */
fun interface ScreenshotRecognizer {
  /** 返回 null 表示这张图解码不了或识别失败，调用方应跳过它而不是中断整批。 */
  suspend fun recognize(bytes: ByteArray): RecognizedScreenshot?
}

/** 生产实现：ML Kit 中文识别。 */
class MlKitScreenshotRecognizer : ScreenshotRecognizer, AutoCloseable {

  private val ocr = ScreenshotOcr()

  override suspend fun recognize(bytes: ByteArray): RecognizedScreenshot? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val width = bitmap.width
    val height = bitmap.height
    return try {
      RecognizedScreenshot(lines = ocr.recognize(bitmap), width = width, height = height)
    } finally {
      bitmap.recycle()
    }
  }

  override fun close() {
    ocr.close()
  }
}
