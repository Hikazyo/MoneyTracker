package com.moneytracker.app.data.ocr

/**
 * 一行 OCR 识别结果及其在图片中的包围盒（单位：像素）。
 *
 * 解析层**只依赖这个类型**，不依赖 ML Kit。这样所有解析规则都能在 JVM 单测里跑，
 * 既不需要设备，也不会因为换识别引擎而全部重写。
 */
data class OcrLine(
  val text: String,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  val width: Int get() = right - left

  val height: Int get() = bottom - top

  val centerY: Int get() = (top + bottom) / 2

  val centerX: Int get() = (left + right) / 2
}
