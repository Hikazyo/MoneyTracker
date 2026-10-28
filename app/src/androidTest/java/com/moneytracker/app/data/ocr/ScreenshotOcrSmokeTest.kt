package com.moneytracker.app.data.ocr

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端到端冒烟测试：真实图片 → ML Kit → 解析器。
 *
 * 这一层**只断言「能跑通、笔数对、关键金额对」**，不断言商户原文——
 * 商户名会随模型版本漂移（`美团` 实测会被识别成 `姜团`），拿它当断言等于自造 flaky 测试。
 * 金额与笔数才是记账的正确性所在，所以只盯它们。
 *
 * 需要模拟器/真机；JVM 单测跑的是同一个解析器的 fixture 版本（见 ScreenshotParserTest）。
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotOcrSmokeTest {

  @Test
  fun every_sample_recognizes_and_parses_the_expected_rows() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().context
    val names = (context.assets.list("") ?: emptyArray())
      .filter { it.endsWith(".jpg", ignoreCase = true) }
      .sorted()
    // 样例截图是真实账单，不随仓库发布（见 .git/info/exclude）：
    // 别人 clone 下来这里会自动跳过，而不是跑出一片红。
    Assume.assumeTrue("缺少本机测试截图（含真实账单，不入库），跳过", names.size == 4)

    val expectedRows = mapOf("sample1.jpg" to 9, "sample2.jpg" to 9, "sample3.jpg" to 7, "sample4.jpg" to 9)

    val ocr = ScreenshotOcr()
    try {
      for (name in names) {
        val bytes = context.assets.open(name).use { it.readBytes() }
        val bitmap =
          checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "$name 解码失败" }
        val width = bitmap.width
        val height = bitmap.height

        val lines = ocr.recognize(bitmap)
        bitmap.recycle()

        assertTrue("$name 没有识别出任何文本行", lines.isNotEmpty())

        val result =
          ScreenshotParser().parse(
            lines = lines,
            imageWidth = width,
            imageHeight = height,
            imageKey = ScreenshotOcr.imageKey(bytes),
            fileName = name,
          )

        assertEquals("$name 解析出的笔数不对", expectedRows[name], result.entries.size)
      }
    } finally {
      ocr.close()
    }
  }

  @Test
  fun bank_sample_keeps_the_row_whose_minus_sign_became_a_chinese_character() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().context
    val available = (context.assets.list("") ?: emptyArray()).contains("sample4.jpg")
    Assume.assumeTrue("缺少本机测试截图（含真实账单，不入库），跳过", available)
    val bytes = context.assets.open("sample4.jpg").use { it.readBytes() }
    val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    val width = bitmap.width
    val height = bitmap.height

    val ocr = ScreenshotOcr()
    val lines = try {
      ocr.recognize(bitmap)
    } finally {
      bitmap.recycle()
      ocr.close()
    }

    val result =
      ScreenshotParser().parse(
        lines = lines,
        imageWidth = width,
        imageHeight = height,
        imageKey = ScreenshotOcr.imageKey(bytes),
        fileName = "sample4.jpg",
      )

    // `-¥14.98` 会被识别成 `一¥14.98`；14.98 那一笔不能因此消失，也不能变成收入
    val entry = result.entries.firstOrNull { it.amountMinor == 1498L }
    assertTrue("14.98 那一笔丢了", entry != null)
    assertEquals(com.moneytracker.app.data.model.TransactionType.EXPENSE, entry!!.type)
  }
}
