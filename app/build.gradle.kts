// 必须显式 import：在 .kts 构建脚本里，`java` 会优先解析成 Gradle 的 Java 插件扩展，
// 直接写 java.util.Properties 会报 Unresolved reference 'util'
import java.io.File
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
}

// 发布签名从工程**外部**读取，按优先级尝试三个位置：
//
//   1. -Pkeystore.properties=<路径>      显式指定（CI 用）
//   2. ~/.android-keys/moneytracker/     推荐位置，密钥不在工程目录里
//   3. <工程根>/keystore.properties       兼容旧布局
//
// 为什么默认放在工程外：密钥只要躺在工程目录里，就迟早会被某个打包命令
// （zip/rar/发给别人看代码）一起带走。本项目就发生过一次——分发包里同时含
// 私钥与明文口令，拿到的人可以签出被系统认可的「升级包」。把密钥移出工程，
// 这类事故就不必依赖每个人的记性来避免。
//
// 三个位置都没有时回退到 debug 签名，保证其他人 clone 之后仍然能构建。
val keystorePropertiesFile: File? =
    run {
        val explicit = (findProperty("keystore.properties") as String?)?.let(::file)
        val external =
            File(System.getProperty("user.home"), ".android-keys/moneytracker/keystore.properties")
        val legacy = rootProject.file("keystore.properties")
        listOfNotNull(explicit, external, legacy).firstOrNull { it.exists() }
    }

val keystoreProperties =
    Properties().apply {
        keystorePropertiesFile?.inputStream()?.use { load(it) }
    }

/** 密钥库路径以 keystore.properties 所在目录为基准，这样整个密钥目录可以整体搬走。 */
val keystoreDir: File? = keystorePropertiesFile?.parentFile

android {
    namespace = "com.moneytracker.app"
    compileSdk = 36
    // 固定 build-tools 版本：36.1.0 由国内镜像安装（见 D:\Android\sdk-mirror-install.ps1）
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "com.moneytracker.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        if (keystorePropertiesFile != null) {
            create("release") {
                storeFile = File(keystoreDir, keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // 启用 v3 签名。v2 在 minSdk 24 上已经够用，v3 的价值在于**密钥轮换**：
                // 没有它，一旦需要换签就只能让所有用户卸载重装。
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 开启代码压缩与资源压缩。反射相关的库需要 proguard 规则，
            // 见 app/proguard-rules.pro——开启后必须实测，不能只看编译通过。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                if (keystorePropertiesFile != null) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
            // 截图识别用的 ML Kit 捆绑模型带的是 native 库，而 native 库**不压缩**，
            // 四套 ABI 会让包体多出 41 MB（实测 9.19 MB → 50.71 MB）。
            // 只发 ARM：覆盖几乎所有真实手机，包体落在 24 MB 左右。
            //
            // debug 刻意不裁：x86_64 模拟器要能装、要能跑插桩测试。
            // 代价是 x86 设备装不了 release 包——本项目本来就只通过 GitHub Releases 分发 APK，
            // 而几乎没有真实设备是 x86 的。
            ndk {
                abiFilters.clear()
                abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 24 上使用 java.time 必须开启脱糖（java.time 原生要求 API 26）
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

// Room 编译期校验 schema，并把生成的 schema JSON 落到 schemas/ 便于版本迁移对比
room {
    schemaDirectory("$projectDir/schemas")
}

// 把导出的 schema 挂进 androidTest 的资源，MigrationTestHelper 才能读到历史版本
android {
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.kotlinx.coroutines.test)
  // 迁移测试需要读导出的 schema
  androidTestImplementation(libs.androidx.room.testing)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Room：本地数据库
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  // 数据库加密：账本以 SQLCipher 加密后落盘（口令由 Android Keystore 保管）
  implementation(libs.sqlcipher.android)

  // minSdk 24 上使用 java.time 所需的脱糖库
  coreLibraryDesugaring(libs.desugar.jdk.libs)

  // 备份文件的序列化（插件已在 plugins 块中应用）
  implementation(libs.kotlinx.serialization.json)

  // 截图记账：本机文字识别。捆绑模型，因此**运行时不联网**。
  // 注意：它的 AAR 清单会带进 INTERNET / ACCESS_NETWORK_STATE，已在 AndroidManifest.xml
  // 里用 tools:node="remove" 剔除（见 DESIGN.md §十二「权限与体积」）。
  implementation(libs.mlkit.text.recognition.chinese)
}
