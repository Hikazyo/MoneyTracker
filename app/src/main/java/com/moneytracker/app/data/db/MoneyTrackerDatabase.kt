package com.moneytracker.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.moneytracker.app.data.db.dao.BudgetDao
import com.moneytracker.app.data.db.dao.CategoryDao
import com.moneytracker.app.data.db.dao.TransactionDao
import com.moneytracker.app.data.db.entity.CategoryBudgetEntity
import com.moneytracker.app.data.db.entity.CategoryEntity
import com.moneytracker.app.data.db.entity.MonthlyBudgetEntity
import com.moneytracker.app.data.db.entity.TransactionEntity
import com.moneytracker.app.data.model.TransactionType

@Database(
  entities =
    [
      CategoryEntity::class,
      TransactionEntity::class,
      MonthlyBudgetEntity::class,
      CategoryBudgetEntity::class,
    ],
  version = 3,
  exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MoneyTrackerDatabase : RoomDatabase() {

  abstract fun categoryDao(): CategoryDao

  abstract fun transactionDao(): TransactionDao

  abstract fun budgetDao(): BudgetDao

  companion object {
    const val DB_NAME = "moneytracker.db"

    fun build(context: Context): MoneyTrackerDatabase =
      Room.databaseBuilder(context.applicationContext, MoneyTrackerDatabase::class.java, DB_NAME)
        .addCallback(SeedCallback)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()

    /**
     * 内存库构造，供仪器测试使用。
     *
     * 刻意复用同一个 [SeedCallback]：若测试自己建库而漏掉回调，
     * 测试跑的建库路径就与生产不一致，种子数据的问题将测不出来。
     */
    fun buildInMemory(context: Context): MoneyTrackerDatabase =
      Room.inMemoryDatabaseBuilder(context, MoneyTrackerDatabase::class.java)
        .addCallback(SeedCallback)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()

    /**
     * v1 → v2：账目表新增 `sourceId`（外部账单来源标识）并建唯一索引。
     *
     * **注意不能写成 `ADD COLUMN sourceId TEXT DEFAULT NULL`**：显式写 DEFAULT NULL 会让
     * SQLite 把 `dflt_value` 记成字符串 `'NULL'`，而 Room 期望的是真正的 null，
     * 于是启动时校验 schema 失败并抛异常。可空列不写 DEFAULT 即可，语义相同。
     */
    val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE transactions ADD COLUMN sourceId TEXT")
          db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_transactions_sourceId " +
              "ON transactions (sourceId)"
          )
        }
      }

    /**
     * v2 → v3：为**已有用户**补写 v3 新增的内置分类。
     *
     * 为什么不改种子列表就够了：种子数据只在 `onCreate`（首次建库）时写入，
     * 老用户的库早就建好了，永远不会再执行 onCreate。只加种子不加迁移，
     * 结果是「新装用户有这些分类、升级用户没有」，而且没有任何报错。
     *
     * 三个细节：
     *
     *  1. **只插新增的那批**，不能在老库里重插 v1 的分类，否则会出现同名重复。
     *  2. **同名同方向的分类已存在就跳过**：用户完全可能早就自己建了一个「宠物」。
     *     再插一个内置的会让列表里出现两个「宠物」，用户在记账时根本分不清选哪个。
     *  3. **把已有的自定义分类整体后移**，给新内置分类腾出位置。否则新分类的
     *     sortOrder 会和用户自定义分类撞在一起，显示顺序变成「内置、自定义、内置、自定义」
     *     交错。后移量为新增数量，这样升级后的顺序与新装时完全一致。
     */
    val MIGRATION_2_3 =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          TransactionType.entries.forEach { type -> addMissingBuiltIns(db, type) }
        }

        private fun addMissingBuiltIns(db: SupportSQLiteDatabase, type: TransactionType) {
          val additions = SeedData.addedInV3(type)
          if (additions.isEmpty()) return

          // 腾位置：自定义分类永远排在所有内置分类之后
          db.execSQL(
            "UPDATE categories SET sortOrder = sortOrder + ? WHERE type = ? AND isBuiltIn = 0",
            arrayOf<Any>(additions.size, type.name),
          )

          var order = SeedData.v1Count(type)
          additions.forEach { seed ->
            db.execSQL(
              "INSERT INTO categories (name, iconKey, type, sortOrder, isBuiltIn, isArchived) " +
                "SELECT ?, ?, ?, ?, 1, 0 " +
                "WHERE NOT EXISTS (" +
                "SELECT 1 FROM categories WHERE name = ? AND type = ?" +
                ")",
              arrayOf<Any>(
                seed.name,
                seed.iconKey,
                type.name,
                order,
                seed.name,
                type.name,
              ),
            )
            // 名字被占用而跳过时也会留下空位，这是可接受的：顺序仍然单调
            order++
          }
        }
      }

    /**
     * 首次建库时写入内置分类。
     *
     * 用裸 SQL 而不是 DAO：onCreate 执行时数据库实例尚未构造完成，还拿不到 DAO（循环依赖）。
     * onCreate 与建表处于同一事务中，因此种子数据要么全部写入、要么全部回滚。
     *
     * sortOrder **按方向各自从 0 开始**，与 [MIGRATION_2_3] 的算法保持一致，
     * 这样新装与升级后的库在顺序上完全相同。
     */
    private object SeedCallback : RoomDatabase.Callback() {
      override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        TransactionType.entries.forEach { type ->
          SeedData.categories(type).forEachIndexed { index, seed ->
            db.execSQL(
              "INSERT INTO categories (name, iconKey, type, sortOrder, isBuiltIn, isArchived) " +
                "VALUES (?, ?, ?, ?, 1, 0)",
              arrayOf<Any>(seed.name, seed.iconKey, type.name, index),
            )
          }
        }
      }
    }
  }
}
