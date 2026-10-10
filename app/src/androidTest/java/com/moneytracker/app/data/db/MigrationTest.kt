package com.moneytracker.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moneytracker.app.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库迁移测试。
 *
 * 存在的价值：**迁移里最容易翻车的地方是「迁移后 schema 与实体对不上」**，
 * 而这种错误不会在编译期暴露，只会在用户升级 App 启动时抛异常。
 * `runMigrationsAndValidate` 会拿迁移后的库与实体声明的 schema 逐列比对。
 *
 * 本项目就踩过一次：`ALTER TABLE ... ADD COLUMN sourceId TEXT DEFAULT NULL`
 * 会让 SQLite 把 `dflt_value` 记成字符串 `'NULL'`，而 Room 期望真正的 null，校验直接失败。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

  @get:Rule
  val helper: MigrationTestHelper =
    MigrationTestHelper(
      InstrumentationRegistry.getInstrumentation(),
      MoneyTrackerDatabase::class.java,
    )

  private fun seedOneCategory(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    db.execSQL(
      "INSERT INTO categories (id, name, iconKey, type, sortOrder, isBuiltIn, isArchived) " +
        "VALUES (1, '餐饮', 'food', 'EXPENSE', 0, 1, 0)"
    )
  }

  @Test
  fun migration_1_to_2_adds_sourceId_and_keeps_existing_rows() {
    val dbName = "migration-1-2"

    helper.createDatabase(dbName, 1).use { db ->
      seedOneCategory(db)
      // 按 v1 的表结构插入一条老数据（此时还没有 sourceId 列）
      db.execSQL(
        "INSERT INTO transactions (amountMinor, type, categoryId, note, occurredAt, createdAt, updatedAt) " +
          "VALUES (3200, 'EXPENSE', 1, '午饭', 1000, 1000, 1000)"
      )
    }

    // 这一步会校验迁移后的 schema 与实体声明完全一致，不一致就抛异常
    val migrated =
      helper.runMigrationsAndValidate(dbName, 2, true, MoneyTrackerDatabase.MIGRATION_1_2)

    migrated.query("SELECT amountMinor, sourceId FROM transactions").use { cursor ->
      assertTrue("老数据必须保留", cursor.moveToFirst())
      assertEquals(3200L, cursor.getLong(0))
      assertTrue("老数据的 sourceId 应为 NULL", cursor.isNull(1))
      assertEquals(1, cursor.count)
    }
  }

  @Test
  fun unique_index_blocks_duplicate_source_ids_after_migration() {
    val dbName = "migration-unique"

    helper.createDatabase(dbName, 1).use { db ->
      seedOneCategory(db)
    }
    val migrated =
      helper.runMigrationsAndValidate(dbName, 2, true, MoneyTrackerDatabase.MIGRATION_1_2)

    migrated.execSQL(
      "INSERT INTO transactions (amountMinor, type, categoryId, occurredAt, createdAt, updatedAt, sourceId) " +
        "VALUES (100, 'EXPENSE', 1, 1, 1, 1, 'WECHAT:1')"
    )

    var blocked = false
    try {
      migrated.execSQL(
        "INSERT INTO transactions (amountMinor, type, categoryId, occurredAt, createdAt, updatedAt, sourceId) " +
          "VALUES (200, 'EXPENSE', 1, 2, 2, 2, 'WECHAT:1')"
      )
    } catch (e: Exception) {
      blocked = true
    }
    assertTrue("唯一索引必须挡住重复来源，否则导入去重会失效", blocked)
  }

  @Test
  fun multiple_null_source_ids_are_allowed() {
    // 手工记录的账目 sourceId 都是 NULL，唯一索引不能因此把它们判为重复
    val dbName = "migration-null-source"

    helper.createDatabase(dbName, 1).use { db ->
      seedOneCategory(db)
    }
    val migrated =
      helper.runMigrationsAndValidate(dbName, 2, true, MoneyTrackerDatabase.MIGRATION_1_2)

    repeat(3) { index ->
      migrated.execSQL(
        "INSERT INTO transactions (amountMinor, type, categoryId, occurredAt, createdAt, updatedAt, sourceId) " +
          "VALUES (100, 'EXPENSE', 1, $index, 1, 1, NULL)"
      )
    }

    migrated.query("SELECT COUNT(*) FROM transactions").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("SQLite 的唯一索引对 NULL 不生效，这是我们需要的行为", 3, cursor.getInt(0))
    }
  }

  // ---------- v2 → v3：为老用户补写新增的内置分类 ----------

  /**
   * 造出「一个真实的老库」：v1 那批内置分类，sortOrder 按方向各自从 0 开始。
   *
   * 用 `SeedData` 里记录的 v1 数量来切，而不是在这里手抄一份分类名单——
   * 手抄的那份迟早会和真实数据走样，而走样了测试还是绿的。
   */
  private fun seedV1BuiltIns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    TransactionType.entries.forEach { type ->
      SeedData.categories(type).take(SeedData.v1Count(type)).forEachIndexed { index, seed ->
        db.execSQL(
          "INSERT INTO categories (name, iconKey, type, sortOrder, isBuiltIn, isArchived) " +
            "VALUES (?, ?, ?, ?, 1, 0)",
          arrayOf<Any>(seed.name, seed.iconKey, type.name, index),
        )
      }
    }
  }

  private fun SupportSQLiteDatabase.categoryNames(type: String): List<String> =
    query("SELECT name FROM categories WHERE type = ? ORDER BY sortOrder, id", arrayOf(type)).use {
      cursor ->
      buildList {
        while (cursor.moveToNext()) add(cursor.getString(0))
      }
    }

  private fun SupportSQLiteDatabase.countOf(name: String): Int =
    query("SELECT COUNT(*) FROM categories WHERE name = ?", arrayOf(name)).use { cursor ->
      cursor.moveToFirst()
      cursor.getInt(0)
    }

  private fun SupportSQLiteDatabase.sortOrderOf(name: String): Int =
    query("SELECT sortOrder FROM categories WHERE name = ?", arrayOf(name)).use { cursor ->
      cursor.moveToFirst()
      cursor.getInt(0)
    }

  private fun SupportSQLiteDatabase.isBuiltIn(name: String): Int =
    query("SELECT isBuiltIn FROM categories WHERE name = ?", arrayOf(name)).use { cursor ->
      cursor.moveToFirst()
      cursor.getInt(0)
    }

  @Test
  fun migration_2_to_3_gives_existing_users_all_the_new_categories() {
    // 这条是这个迁移存在的全部理由：种子数据只在首次建库时写入，
    // 不写迁移的话，老用户升级后根本看不到新分类，而且没有任何报错。
    val dbName = "migration-2-3-adds"

    helper.createDatabase(dbName, 2).use { db -> seedV1BuiltIns(db) }
    val migrated =
      helper.runMigrationsAndValidate(dbName, 3, true, MoneyTrackerDatabase.MIGRATION_2_3)

    val expense = migrated.categoryNames("EXPENSE")
    val income = migrated.categoryNames("INCOME")

    assertEquals("升级后应当拥有完整的分类集", SeedData.EXPENSE_CATEGORIES.size, expense.size)
    assertEquals(SeedData.INCOME_CATEGORIES.size, income.size)
    assertEquals(SeedData.EXPENSE_CATEGORIES.map { it.name }, expense)
    assertEquals(SeedData.INCOME_CATEGORIES.map { it.name }, income)
  }

  @Test
  fun migration_2_to_3_does_not_duplicate_the_categories_that_already_existed() {
    val dbName = "migration-2-3-no-duplicates"

    helper.createDatabase(dbName, 2).use { db -> seedV1BuiltIns(db) }
    val migrated =
      helper.runMigrationsAndValidate(dbName, 3, true, MoneyTrackerDatabase.MIGRATION_2_3)

    // 老分类不能因为迁移而多出一份，否则分类列表里会出现两个「餐饮」
    listOf("餐饮", "交通", "工资", "其他支出").forEach { name ->
      assertEquals("「$name」被重复插入了", 1, migrated.countOf(name))
    }
    migrated.query("SELECT COUNT(*) FROM categories").use { cursor ->
      cursor.moveToFirst()
      assertEquals(SeedData.ALL.size, cursor.getInt(0))
    }
  }

  @Test
  fun migration_2_to_3_skips_a_new_category_the_user_already_created() {
    // 用户完全可能早就自己建了一个「宠物」。
    // 再插一个内置的会让列表里出现两个「宠物」，而记账时根本分不清该选哪个。
    val dbName = "migration-2-3-name-taken"

    helper.createDatabase(dbName, 2).use { db ->
      seedV1BuiltIns(db)
      db.execSQL(
        "INSERT INTO categories (name, iconKey, type, sortOrder, isBuiltIn, isArchived) " +
          "VALUES ('宠物', 'heart', 'EXPENSE', 10, 0, 0)"
      )
    }
    val migrated =
      helper.runMigrationsAndValidate(dbName, 3, true, MoneyTrackerDatabase.MIGRATION_2_3)

    assertEquals("同名分类只应有一个", 1, migrated.countOf("宠物"))
    assertEquals(
      "应当保留用户自己建的那个，而不是换成内置的",
      0,
      migrated.isBuiltIn("宠物"),
    )
  }

  @Test
  fun migration_2_to_3_puts_the_new_categories_after_the_existing_built_ins() {
    val dbName = "migration-2-3-order"

    helper.createDatabase(dbName, 2).use { db -> seedV1BuiltIns(db) }
    val migrated =
      helper.runMigrationsAndValidate(dbName, 3, true, MoneyTrackerDatabase.MIGRATION_2_3)

    // v1 的最后一个支出分类 sortOrder = 9，新增的应当从 10 接着排
    val v1Count = SeedData.v1Count(TransactionType.EXPENSE)
    SeedData.addedInV3(TransactionType.EXPENSE).forEachIndexed { index, seed ->
      assertEquals(
        "「${seed.name}」的排序位不对",
        v1Count + index,
        migrated.sortOrderOf(seed.name),
      )
    }
  }

  @Test
  fun migration_2_to_3_moves_user_categories_behind_the_new_built_ins() {
    // 不后移的话，新内置分类的 sortOrder 会和用户自定义分类撞在一起，
    // 显示顺序会变成「内置、自定义、内置、自定义」交错
    val dbName = "migration-2-3-shift"

    helper.createDatabase(dbName, 2).use { db ->
      seedV1BuiltIns(db)
      // 真实情况下自定义分类的 sortOrder 一定是 MAX+1，即 10
      db.execSQL(
        "INSERT INTO categories (name, iconKey, type, sortOrder, isBuiltIn, isArchived) " +
          "VALUES ('我的分类', 'star', 'EXPENSE', 10, 0, 0)"
      )
    }
    val migrated =
      helper.runMigrationsAndValidate(dbName, 3, true, MoneyTrackerDatabase.MIGRATION_2_3)

    val added = SeedData.addedInV3(TransactionType.EXPENSE).size
    assertEquals("自定义分类应整体后移，让新内置分类插在前面", 10 + added, migrated.sortOrderOf("我的分类"))
    assertEquals(false, migrated.isBuiltIn("我的分类") == 1)
  }

  @Test
  fun migration_2_to_3_keeps_existing_transactions_untouched() {
    val dbName = "migration-2-3-data"

    helper.createDatabase(dbName, 2).use { db ->
      seedV1BuiltIns(db)
      db.execSQL(
        "INSERT INTO transactions (amountMinor, type, categoryId, note, occurredAt, createdAt, updatedAt, sourceId) " +
          "SELECT 3200, 'EXPENSE', id, '午饭', 1000, 1000, 1000, 'WECHAT:1' FROM categories WHERE name = '餐饮'"
      )
    }
    val migrated =
      helper.runMigrationsAndValidate(dbName, 3, true, MoneyTrackerDatabase.MIGRATION_2_3)

    migrated.query("SELECT amountMinor, note, sourceId FROM transactions").use { cursor ->
      assertTrue("账目必须原样保留", cursor.moveToFirst())
      assertEquals(3200L, cursor.getLong(0))
      assertEquals("午饭", cursor.getString(1))
      assertEquals("WECHAT:1", cursor.getString(2))
      assertEquals(1, cursor.count)
    }
  }

  @Test
  fun migrating_all_the_way_from_v1_lands_on_the_same_categories() {
    // 从 v1 一路升上来的用户，最终分类集应当和新装用户完全一致
    val dbName = "migration-1-3"

    helper.createDatabase(dbName, 1).use { db ->
      // v1 的种子写入方式与 onCreate 相同（此处手工模拟）
      seedV1BuiltIns(db)
    }
    val migrated =
      helper.runMigrationsAndValidate(
        dbName,
        3,
        true,
        MoneyTrackerDatabase.MIGRATION_1_2,
        MoneyTrackerDatabase.MIGRATION_2_3,
      )

    assertEquals(
      SeedData.EXPENSE_CATEGORIES.map { it.name },
      migrated.categoryNames("EXPENSE"),
    )
  }
}
