# ============================================================
# R8 / ProGuard 规则
#
# 开启 minify 后，反射与代码生成相关的库最容易出问题：
# 它们靠运行期按名字查找类/方法，混淆后名字变了就找不到。
# 本项目涉及两处，规则见下。
# ============================================================

# ---------- kotlinx.serialization ----------
# 备份文件用 @Serializable，R8 若不保留这些成员，
# 生成的 serializer 会被当成无用代码删掉，恢复备份时直接抛异常。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.moneytracker.app.data.backup.**$$serializer { *; }
-keepclassmembers class com.moneytracker.app.data.backup.** {
    *** Companion;
}
-keepclasseswithmembers class com.moneytracker.app.data.backup.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------- Room ----------
# Room 的 consumer rules 已随 AAR 提供，这里无需重复。
# 若将来出现数据库相关崩溃，可打开下面的规则排查：
#-keep class * extends androidx.room.RoomDatabase { <init>(); }
#-dontwarn androidx.room.paging.**

# ---------- 保留行号，便于线上崩溃定位 ----------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
