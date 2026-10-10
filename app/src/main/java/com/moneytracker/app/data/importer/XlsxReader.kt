package com.moneytracker.app.data.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * 极简 xlsx（OOXML）读取器。
 *
 * **为什么自己写而不用 Apache POI**：微信账单导出的就是 xlsx，所以必须支持。
 * 但 `poi-ooxml` 会拖进 xmlbeans、commons-compress 等一大堆依赖，release 包会从
 * 1.8 MB 涨到十几 MB，而且 POI 依赖 `java.awt` 与部分 `javax.xml.stream` 实现，
 * 在 Android 上历来需要额外打补丁。
 *
 * 我们只需要「把第一个工作表读成字符串二维数组」这一件事，而 xlsx 本质就是一个
 * ZIP 里装着几个 XML，`ZipInputStream` 与 SAX 都是 Android 与 JVM 自带的，
 * 因此零依赖、零体积增长。
 *
 * 需要处理的 OOXML 细节（都是真实文件里会遇到的）：
 *
 *  1. **单元格会跳格**：`<c r="A1"/>` 后面可能直接是 `<c r="C1"/>`，B 列为空。
 *     因此必须解析 `r` 属性算出列号，**绝不能按出现顺序依次追加**——否则整行列错位。
 *  2. **行也会跳**：`<row r="7">` 可能紧跟在 `<row r="4">` 之后。
 *  3. **文本有两种存法**：共享字符串表（`t="s"`，值是 `sharedStrings.xml` 的下标）
 *     与内联字符串（`t="inlineStr"`）。只认一种就会读出空表格。
 *  4. **数值可能是科学计数法**（`2.3E+1`），直接当字符串用会让金额解析失败。
 *  5. **结尾空单元格要裁掉**：账单开头的说明行只有 A 列有内容，
 *     不裁掉的话每一行都「有 11 列」，[BillImporter] 判定表头的「至少 2 格」规则就失效了。
 */
object XlsxReader {

  /** 行数上限，挡住畸形文件把内存吃满。真实账单远小于这个数。 */
  private const val MAX_ROWS = 60_000

  /** 列数上限，挡住 `r="XFD1048576"` 这类合法但离谱的坐标。 */
  private const val MAX_COLUMNS = 256

  /**
   * 单个 ZIP 条目**解压后**的字节上限：32 MB。
   *
   * 必须限制的是解压后的体积，而不是压缩包本身的大小：xlsx 是 ZIP，
   * 一个几十 KB 的压缩包可以膨胀成几 GB（压缩炸弹），而 `readBytes()`
   * 会老老实实把它全部读进内存。这是本项目里唯一一处
   * 「用户随手选一个文件就能让应用崩掉」的路径。
   */
  private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

  /**
   * 共享字符串条数上限。
   *
   * 这张表是**无差别整表加载**的（不像工作表那样按行截断），
   * 因此它是 xlsx 路径上最容易失控的结构。20 万条远超任何真实账单的需要：
   * 微信的年度账单通常在几千条量级。
   */
  private const val MAX_SHARED_STRINGS = 200_000

  private const val ENTRY_WORKBOOK = "xl/workbook.xml"
  private const val ENTRY_RELS = "xl/_rels/workbook.xml.rels"
  private const val ENTRY_SHARED_STRINGS = "xl/sharedStrings.xml"

  /** 是不是 ZIP 容器。xlsx 靠它判定；加密的账单压缩包也会命中，交给上层给提示。 */
  fun isZip(bytes: ByteArray): Boolean =
    bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

  /**
   * 读出第一个工作表；返回 null 表示这虽然是个 ZIP，但读不出工作表
   * （典型情况：微信账单邮件里的加密压缩包，需要先解压）。
   */
  fun read(bytes: ByteArray): List<List<String>>? {
    val names = mutableListOf<String>()
    var workbookXml: ByteArray? = null
    var relsXml: ByteArray? = null
    var sharedXml: ByteArray? = null

    // 第一遍：收集条目名与三个小文件。
    // 必须分成两遍，因为 sharedStrings.xml 未必排在工作表之前，
    // 而解析工作表时要用到共享字符串表。
    // （ZipInputStream 的 nextEntry 会自动跳过当前条目剩余字节，所以这里不必逐个消费。）
    forEachEntry(bytes) { name, stream ->
      names.add(name)
      when (name) {
        ENTRY_WORKBOOK -> workbookXml = stream.readBounded()
        ENTRY_RELS -> relsXml = stream.readBounded()
        ENTRY_SHARED_STRINGS -> sharedXml = stream.readBounded()
        else -> Unit
      }
    }

    val sheetPath = resolveSheetPath(workbookXml, relsXml, names) ?: return null
    val shared = sharedXml?.let(::parseSharedStrings) ?: emptyList()

    // 第二遍：流式解析工作表本身（可能有几千行，不整个读进内存）
    var rows: List<List<String>>? = null
    forEachEntry(bytes) { name, stream ->
      if (rows == null && name == sheetPath) {
        val handler = SheetHandler(shared)
        rows =
          runCatching {
              parseXml(stream, handler)
              handler.result()
            }
            .getOrNull()
      }
    }
    return rows
  }

  // ---------- 定位工作表 ----------

  /**
   * 找出「工作簿里的第一个工作表」对应的 ZIP 条目路径。
   *
   * 正规做法是 workbook.xml 给出 `r:id`，再由 rels 映射到真正的文件——
   * 因为工作表文件名未必叫 sheet1.xml。但这两个文件都可能缺失或不合规范，
   * 所以逐级降级到「按文件名挑第一个工作表」。
   */
  private fun resolveSheetPath(
    workbookXml: ByteArray?,
    relsXml: ByteArray?,
    names: List<String>,
  ): String? {
    val relId = workbookXml?.let(::firstSheetRelId)
    val target = relId?.let { id -> relsXml?.let { relTarget(it, id) } }
    val resolved = target?.let(::normalizeTarget)
    if (resolved != null && names.contains(resolved)) return resolved

    // 降级 1：按 xl/worksheets/sheetN.xml 的数字顺序取第一个
    val numbered =
      names
        .mapNotNull { name ->
          Regex("""^xl/worksheets/sheet(\d+)\.xml$""").find(name)?.let { m ->
            m.groupValues[1].toInt() to name
          }
        }
        .minByOrNull { it.first }
        ?.second
    if (numbered != null) return numbered

    // 降级 2：任意一个工作表文件
    return names.firstOrNull { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
  }

  private fun firstSheetRelId(workbookXml: ByteArray): String? {
    var id: String? = null
    val handler =
      object : DefaultHandler() {
        override fun startElement(
          uri: String?,
          localName: String?,
          qName: String?,
          attributes: Attributes,
        ) {
          if (id == null && elementName(localName, qName) == "sheet") {
            id = attributes.attr("id") // 对应 r:id
          }
        }
      }
    runCatching { parseXml(ByteArrayInputStream(workbookXml), handler) }
    return id
  }

  private fun relTarget(relsXml: ByteArray, relId: String): String? {
    var target: String? = null
    val handler =
      object : DefaultHandler() {
        override fun startElement(
          uri: String?,
          localName: String?,
          qName: String?,
          attributes: Attributes,
        ) {
          if (target == null &&
            elementName(localName, qName) == "Relationship" &&
            attributes.attr("Id") == relId
          ) {
            target = attributes.attr("Target")
          }
        }
      }
    runCatching { parseXml(ByteArrayInputStream(relsXml), handler) }
    return target
  }

  /** rels 里的 Target 是相对 `xl/` 的，也可能是以 `/` 开头的绝对路径。 */
  private fun normalizeTarget(target: String): String {
    val cleaned = target.trim().replace('\\', '/')
    if (cleaned.startsWith("/")) return cleaned.removePrefix("/")
    val path = if (cleaned.startsWith("xl/")) cleaned else "xl/$cleaned"
    return collapseDotSegments(path)
  }

  private fun collapseDotSegments(path: String): String {
    val out = mutableListOf<String>()
    path.split('/').forEach { segment ->
      when (segment) {
        "", "." -> Unit
        ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
        else -> out.add(segment)
      }
    }
    return out.joinToString("/")
  }

  // ---------- 共享字符串表 ----------

  /** `sharedStrings.xml`：每个 `<si>` 是一条，内部可能被 `<r>` 拆成多段 `<t>`。 */
  private fun parseSharedStrings(xml: ByteArray): List<String> {
    val handler = SharedStringsHandler()
    return runCatching {
        parseXml(ByteArrayInputStream(xml), handler)
        handler.result()
      }
      .getOrDefault(emptyList())
  }

  private class SharedStringsHandler : DefaultHandler() {
    private val strings = mutableListOf<String>()
    private val current = StringBuilder()
    private var inItem = false
    private var capturing = false
    private var inPhonetic = false

    fun result(): List<String> = strings

    override fun startElement(
      uri: String?,
      localName: String?,
      qName: String?,
      attributes: Attributes,
    ) {
      when (elementName(localName, qName)) {
        "si" -> {
          inItem = true
          current.clear()
        }
        // 日文注音，不该混进单元格文本
        "rPh" -> inPhonetic = true
        "t" -> capturing = inItem && !inPhonetic
      }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
      if (capturing) current.appendRange(ch, start, start + length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
      when (elementName(localName, qName)) {
        "t" -> capturing = false
        "rPh" -> inPhonetic = false
        "si" -> {
          // 超限时停止累积而不是抛错：已经读到的部分仍然可用，
          // 上层的行数/列数上限会继续兜住后续处理
          if (strings.size < MAX_SHARED_STRINGS) strings.add(current.toString())
          inItem = false
        }
      }
    }
  }

  // ---------- 工作表 ----------

  private class SheetHandler(private val shared: List<String>) : DefaultHandler() {
    private val rows = mutableListOf<MutableList<String>>()
    private var row: MutableList<String>? = null
    private var column = -1
    private var type: String? = null
    private val value = StringBuilder()
    private val inlineText = StringBuilder()
    private var inValue = false
    private var inInlineString = false
    private var inInlineText = false

    fun result(): List<List<String>> = rows.filter { it.isNotEmpty() }

    override fun startElement(
      uri: String?,
      localName: String?,
      qName: String?,
      attributes: Attributes,
    ) {
      when (elementName(localName, qName)) {
        "row" -> startRow(attributes.attr("r")?.toIntOrNull())
        "c" -> {
          column = attributes.attr("r")?.let(::columnIndex) ?: (column + 1)
          type = attributes.attr("t")
          value.clear()
          inlineText.clear()
        }
        "is" -> inInlineString = true
        "v" -> inValue = true
        "t" -> inInlineText = inInlineString
      }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
      if (inValue) value.appendRange(ch, start, start + length)
      if (inInlineText) inlineText.appendRange(ch, start, start + length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
      when (elementName(localName, qName)) {
        "v" -> inValue = false
        "t" -> inInlineText = false
        "is" -> inInlineString = false
        "c" -> putCell()
        "row" -> closeRow()
      }
    }

    private fun startRow(index: Int?) {
      val target = index ?: (rows.size + 1)
      if (target < 1 || target > MAX_ROWS) {
        row = null
        return
      }
      while (rows.size < target) rows.add(mutableListOf())
      row = rows[target - 1]
    }

    private fun closeRow() {
      val current = row ?: return
      // 裁掉结尾空单元格：说明行只有 A 列，不裁就会被当成「多列行」而误判为表头
      while (current.isNotEmpty() && current.last().isEmpty()) current.removeAt(current.size - 1)
      row = null
    }

    private fun putCell() {
      val current = row ?: return
      if (column < 0 || column >= MAX_COLUMNS) return
      val text =
        when (type) {
          "s" -> shared.getOrNull(value.toString().trim().toIntOrNull() ?: -1).orEmpty()
          "inlineStr" -> inlineText.toString()
          "e" -> ""
          "b" -> if (value.toString().trim() == "1") "TRUE" else "FALSE"
          "str" -> value.toString()
          else -> normalizeNumber(value.toString())
        }
      while (current.size <= column) current.add("")
      current[column] = text
    }
  }

  /**
   * 数值单元格的规范化。
   *
   * Excel 写出的数值可能是 `2.3E+1` 这种科学计数法，直接当字符串会解析失败；
   * 也可能是 `23.00` 这种带小数尾巴的。统一转成十进制普通写法，
   * 但**不去动小数位数**——日期序列号的小数部分就是时刻，截断了就丢时间。
   */
  internal fun normalizeNumber(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val decimal = trimmed.toBigDecimalOrNull() ?: return trimmed
    return runCatching { decimal.stripTrailingZeros().toPlainString() }.getOrDefault(trimmed)
  }

  /**
   * 单元格坐标里的列字母转下标：`A` → 0、`Z` → 25、`AA` → 26。
   * 非字母前缀（畸形文件）返回 -1，由调用方跳过。
   */
  internal fun columnIndex(reference: String): Int {
    var index = 0
    var seen = false
    for (c in reference) {
      val upper = c.uppercaseChar()
      if (upper !in 'A'..'Z') break
      index = index * 26 + (upper - 'A' + 1)
      if (index > MAX_COLUMNS) return -1
      seen = true
    }
    return if (seen) index - 1 else -1
  }

  // ---------- 基础设施 ----------

  /**
   * 遍历 ZIP 条目。回调拿到的是 ZIP 流本身，**只有需要的条目才去读**：
   * `ZipInputStream.nextEntry` 会自动跳过当前条目的剩余字节。
   */
  private inline fun forEachEntry(
    bytes: ByteArray,
    action: (name: String, stream: InputStream) -> Unit,
  ) {
    val zip = ZipInputStream(ByteArrayInputStream(bytes))
    while (true) {
      val entry = zip.nextEntry ?: break
      if (!entry.isDirectory) action(entry.name, zip)
    }
  }

  private fun parseXml(stream: InputStream, handler: DefaultHandler) {
    val factory = SAXParserFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isValidating = false
    // 关掉外部实体：畸形/恶意文件不该能让解析器去读本地文件或发网络请求。
    // 各实现对特性名的支持不同，因此逐个忽略失败。
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    val reader = factory.newSAXParser().xmlReader
    reader.contentHandler = handler
    // 关键：解析器读完会**关闭**传入的流，而这里传进来的是 ZIP 的流。
    // 不加这层包装的话，解析完工作表之后再取下一个条目就会抛
    // 「Stream closed」，整个读取直接失败。
    reader.parse(InputSource(BoundedNonClosingStream(stream, MAX_ENTRY_BYTES)))
  }

  /**
   * 只屏蔽 close()，并限制可读字节数，其余原样转发。
   *
   * 关流：解析器读完会关闭传入的流，而这里传进来的是 ZIP 的流。不加这层包装，
   * 解析完工作表之后再取下一个条目就会抛 `Stream closed`。
   *
   * 限流：工作表条目在解析过程中才会暴露真实体积，只能在读取时计数。
   */
  private class BoundedNonClosingStream(
    private val delegate: InputStream,
    private val limit: Long,
  ) : InputStream() {
    private var consumed = 0L

    private fun account(count: Int) {
      if (count <= 0) return
      consumed += count
      if (consumed > limit) {
        throw IOException("表格数据超过 ${limit / 1024 / 1024} MB 上限，已中止解析")
      }
    }

    override fun read(): Int {
      val byte = delegate.read()
      if (byte >= 0) account(1)
      return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      val count = delegate.read(b, off, len)
      account(count)
      return count
    }

    override fun available(): Int = delegate.available()

    override fun close() {
      // 故意不关闭 delegate
    }
  }

  /** 有上限地读完一个条目。 */
  private fun InputStream.readBounded(): ByteArray {
    val buffer = ByteArray(64 * 1024)
    val out = ByteArrayOutputStream()
    var total = 0L
    while (true) {
      val read = read(buffer)
      if (read < 0) break
      total += read
      if (total > MAX_ENTRY_BYTES) {
        throw IOException("表格条目超过 ${MAX_ENTRY_BYTES / 1024 / 1024} MB 上限")
      }
      out.write(buffer, 0, read)
    }
    return out.toByteArray()
  }

  /**
   * 元素名。
   *
   * 开了 namespaceAware 之后，SAX 的 `qName` **不保证有值**
   * （默认 `namespace-prefixes` 为 false 时可能是空串），所以优先取 localName。
   */
  private fun elementName(localName: String?, qName: String?): String =
    localName.orEmpty().ifEmpty { qName.orEmpty() }

  /** 取属性值，同样按下标比对本地名，避开 `qName` 为空的实现差异。 */
  private fun Attributes.attr(name: String): String? {
    for (i in 0 until length) {
      val local = getLocalName(i).orEmpty().ifEmpty { getQName(i).orEmpty() }
      if (local == name) return getValue(i)
    }
    return null
  }
}
