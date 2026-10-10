package com.moneytracker.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvReaderTest {

  @Test
  fun splits_simple_rows() {
    val rows = CsvReader.read("a,b,c\n1,2,3")
    assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows)
  }

  @Test
  fun handles_crlf_line_endings() {
    val rows = CsvReader.read("a,b\r\n1,2\r\n")
    assertEquals(2, rows.size)
    assertEquals(listOf("a", "b"), rows[0])
    assertEquals(listOf("1", "2"), rows[1])
  }

  @Test
  fun handles_cr_only_line_endings() {
    val rows = CsvReader.read("a,b\r1,2")
    assertEquals(2, rows.size)
  }

  @Test
  fun keeps_comma_inside_quoted_field() {
    val rows = CsvReader.read("""a,"买,菜",c""")
    assertEquals(listOf("a", "买,菜", "c"), rows.single())
  }

  @Test
  fun unescapes_doubled_quotes() {
    val rows = CsvReader.read("""a,"他""说",c""")
    assertEquals(listOf("a", "他\"说", "c"), rows.single())
  }

  @Test
  fun keeps_newline_inside_quoted_field() {
    val rows = CsvReader.read("a,\"第一行\n第二行\",c\n1,2,3")
    assertEquals(2, rows.size)
    assertEquals("第一行\n第二行", rows[0][1])
    assertEquals(listOf("1", "2", "3"), rows[1])
  }

  @Test
  fun keeps_empty_fields() {
    val rows = CsvReader.read("a,,c")
    assertEquals(listOf("a", "", "c"), rows.single())
  }

  @Test
  fun drops_rows_that_are_entirely_blank() {
    val rows = CsvReader.read("a,b\n\n\n1,2\n   \n")
    assertEquals(2, rows.size)
  }

  @Test
  fun emits_last_row_without_trailing_newline() {
    val rows = CsvReader.read("a,b\n1,2")
    assertEquals(2, rows.size)
    assertEquals(listOf("1", "2"), rows[1])
  }

  @Test
  fun quoted_field_can_be_empty() {
    val rows = CsvReader.read("""a,"",c""")
    assertEquals(listOf("a", "", "c"), rows.single())
  }

  @Test
  fun trailing_empty_field_is_preserved() {
    // 账单行常以逗号结尾（最后几列留空），末尾空列必须保留，否则列会错位
    val rows = CsvReader.read("a,b,")
    assertEquals(listOf("a", "b", ""), rows.single())
  }
}
