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

# ---------- ML Kit 文字识别（截图记账，v1.4） ----------
#
# **这些规则不能删。** 实测症状：debug 包（不混淆）一切正常，但 release 包
# 一进「截图记账」就整进程崩溃：
#
#   java.lang.NullPointerException: Attempt to read from field 'u7.g u7.f.a'
#     on a null object reference in method 'void f8.k.<init>(int)'
#     ... com.google.android.gms.internal.mlkit_vision_text_bundled_common.oc.i
#
# ML Kit 的模型/组件注册表靠静态初始化与反射建立，R8 把这些内部类当作无用代码
# 裁掉或重排之后，注册表里就是 null。它的 AAR 自带 consumer 规则并不覆盖全部内部类，
# 因此这里整包保留。代价是 dex 大一点，但相对模型 .so 可以忽略。
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
