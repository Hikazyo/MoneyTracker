# 开发环境备忘（MoneyTracker）

踩过的坑与常用命令。改代码前先扫一眼，能省不少时间。

---

## 一、模拟器：UI 测试前必须先唤醒屏幕

**这是本项目最容易浪费时间的一个坑。**

屏幕休眠时（模拟器启动后默认如此），Activity 不会真正进入 resumed 状态，Compose 也就不会挂载。
表现是仪器测试报：

```
java.lang.IllegalStateException: No compose hierarchies found in the app.
Possible reasons include: (1) the Activity that calls setContent did not launch; ...
```

这个报错极具误导性——它让人以为是 `setContent` 写法或 manifest 声明有问题，
**实际原因是屏幕黑着**。判断方法：`adb shell screencap` 截出来是全黑。

### 处理

```powershell
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
& $adb shell input keyevent 224      # KEYCODE_WAKEUP
& $adb shell wm dismiss-keyguard
& $adb shell svc power stayon true   # 保持常亮，避免再次休眠
```

跑 UI / 仪器测试前，先执行这三条。

---

## 二、模拟器要用「长驻后台任务」启动

用 `android emulator start` 启动时，该命令会在模拟器就绪后返回，任务结束，
**模拟器进程会随任务进程树一起被回收**。表现为过了一会儿 `adb devices` 就空了。

正确做法是让 `emulator.exe` 前台跑在一个不会结束的后台任务里：

```powershell
& 'D:\Android\Sdk\emulator\emulator.exe' -avd medium_phone -no-boot-anim
```

这样任务存活期间模拟器就不会被杀。等启动完成：

```powershell
& $adb wait-for-device
# 轮询 sys.boot_completed 直到为 1
```

停止：`adb -s emulator-5554 emu kill`

---

## 三、PowerShell 脚本必须以 UTF-8 BOM 保存

本机 `pwsh` 实际是 **Windows PowerShell 5.1**（不是 7）。无 BOM 的 `.ps1` 会被按
ANSI/GBK 读取，中文注释被破坏后会连带引发莫名其妙的语法错误
（例如报 `Unexpected token '}'`，但那一行根本没有问题）。

**用文件工具改完 `.ps1` 后，务必重新加 BOM：**

```powershell
$p = 'D:\Android\sdk-mirror-install.ps1'
$c = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText($p, $c, (New-Object System.Text.UTF8Encoding($true)))
```

### 更省事的办法：脚本里的字符串字面量全用 ASCII

BOM 很容易在编辑过程中被工具悄悄抹掉（本项目用的 `edit`/`write` 工具就会），
而一旦被抹掉，脚本就**无法运行**，报的还是一句看不懂的语法错误。

所以新脚本（如 `tools/make-dist.ps1`）一律遵循：**所有字符串字面量用英文，
中文只出现在 `#` 注释里**。理由是这样处理之后，无论有没有 BOM 都能正常跑：

- 中文字符串被错误解码时，其中的字节可能被当成引号，直接破坏语法
- 中文注释被错误解码**无害**——注释到行尾就结束了，不影响解析

顺带一提：`npm`/`pnpm` 的 `.ps1` 包装脚本在本机被执行策略拦住，
在 PowerShell 里要用 `npm.cmd`，或先 `Set-ExecutionPolicy -Scope Process Bypass`。
自己写的脚本用 `powershell -ExecutionPolicy Bypass -File <路径>` 调用即可。

### 读取文件时同样要显式指定编码（这个坑踩了两次）

PowerShell 5.1 的 `Get-Content` 对**无 BOM 的 UTF-8 文件默认按 ANSI/GBK 读取**。
用它读取再用 `WriteAllText` 写回，中文会被静默破坏——而且不报错，直到编译时才以
「引号未闭合」「字面量必须用空白分隔」这类完全不相干的错误暴露出来。

```powershell
# 错误：中文会被破坏
$c = Get-Content $file -Raw

# 正确：显式声明 UTF-8
$c = Get-Content $file -Raw -Encoding UTF8
# 或者用 .NET 读，同样显式指定
$c = [System.IO.File]::ReadAllText($file, [System.Text.Encoding]::UTF8)
```

**更稳妥的做法：不要在 PowerShell 里对源码做字符串替换，直接用文件编辑工具改。**

### 控制台中文输出乱码

控制台默认 GBK，Python 等程序输出的中文会变成乱码（数据本身没问题，只是显示）。

```powershell
$env:PYTHONIOENCODING = 'utf-8'
```

判断是不是纯粹的显示问题：看程序里的断言有没有通过。乱码但断言全过，说明数据是对的。

---

## 四、常用命令

```powershell
$env:JAVA_HOME  = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'D:\Android\Sdk'
Set-Location 'D:\dsh arena\AndroidProjects\MoneyTracker'

# 编译
.\gradlew.bat :app:assembleDebug

# 单元测试（JVM，快）
.\gradlew.bat :app:testDebugUnitTest

# 仪器测试（需要模拟器/真机，且屏幕已唤醒）
.\gradlew.bat :app:connectedDebugAndroidTest

# 只看失败用例
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain 2>&1 |
  Select-String -Pattern 'FAILED|failure|Finished \d+ tests'
```

**查看测试报告**（比控制台输出详细得多）：

- 单元测试：`app/build/reports/tests/testDebugUnitTest/index.html`
- 仪器测试：`app/build/reports/androidTests/connected/debug/index.html`

---

## 五、SDK 组件从国内镜像安装

`android` CLI 没有自定义仓库源的选项，所以用脚本装：

```powershell
# 先看会下载什么（不下载）
D:\Android\sdk-mirror-install.ps1 -PackagePath 'platforms;android-37.0' -DryRun

# 安装
D:\Android\sdk-mirror-install.ps1 -PackagePath 'build-tools;36.1.0'
```

详见 `..\镜像配置说明.md`。

---

## 六、几个项目内的约定

| 事项 | 位置 / 说明 |
|---|---|
| Room schema 导出 | `app/schemas/com.moneytracker.app.data.db.MoneyTrackerDatabase/1.json`。**改表结构必须升 version 并写 Migration**，否则线上用户数据会丢 |
| buildToolsVersion | 固定为 `36.1.0`（已装）。去掉这行 AGP 会退回默认版本 |
| 金额单位 | 一律 `Long` 存「分」，恒为正；方向看 `TransactionType`。换算用 `Money` 对象 |
| 月份归属 | 一律按本地时区（`MonthKey` / `startMillis` 等）。**不要**用 UTC 换算 |
| 依赖注入 | 手动 DI，容器在 `MoneyTrackerApp.container`。未引入 Hilt |
| 分类「删除」 | 实为归档（`isArchived`）。外键 RESTRICT 会阻止物理删除有账目的分类 |
| 页面状态持有者 | 短期表单页用「状态持有者 + remember」，长期页用 ViewModel。原因见下节 |

---

## 七、Navigation3 1.0.1 没有导航项级 ViewModel 作用域

**这是本项目最容易踩、也最难查的一个架构约束。**

Navigation3 1.0.1 的构件里**既没有任何 ViewModelStore 相关的装饰器，也没有 NavEntryDecorator
以外的 ViewModel 支持**（`navigation3-runtime` 与 `navigation3-ui` 全查过）。这意味着
`viewModel {}` 会一路回退到 **Activity 的 ViewModelStoreOwner**。

### 造成的实际 bug

编辑页原本写作 `viewModel(key = "edit-${entryId ?: "new"}") { ... }`。由于作用域落在
Activity 上，**同一个 key 在多次导航之间复用同一个实例**：

- 记完一笔（`finished = true`）返回列表；
- 再点「记一笔」→ 同一个 key → 拿到**上次那个实例**；
- `finished` 仍是 true → `LaunchedEffect` 立刻触发 `onDone()`；
- **页面刚出现就自己弹回去了。**

表现极具误导性：第一笔能记，之后每一笔都失败。而且**单元测试和 Compose UI 测试都测不出来**
——它们测的是无状态 content，不涉及导航。这是只有端到端手工跑才会暴露的一类 bug。

### 处理办法

短期页面（表单）改用**普通状态持有者 + `remember(参数)`**，生命周期与导航项一致：

```kotlin
val scope = rememberCoroutineScope()
val holder = remember(entryId) { EditEntryStateHolder(repository, entryId, scope) }
```

长期页面（列表、统计）继续用 `viewModel {}`：它们本来就该在 Activity 生命周期内存活，
「切换月份后返回仍停在那个月」反而是想要的行为。

### 代价与后续

状态持有者不具备配置变更（旋转屏幕）存活能力——这是该版本能力限制下的取舍。
若将来 Navigation3 补上 `rememberViewModelStoreNavEntryDecorator`，
`EditEntryStateHolder` 可以改回 ViewModel，其余代码不用动。

### 通用教训

**「同一路由第二次进入」是一个必须单独验证的场景。** 任何依赖「进入即全新状态」的页面
都要手动走一遍「进 → 操作 → 出 → 再进」，自动化测试在这类问题上是盲区。
`NavigationFlowTest` 就是为守住这个场景而写的。

---

## 八、用 adb 驱动界面做手工验证时的两个坑

排查界面问题时常用 `adb shell input tap` 走流程，有两点必须注意：

### 1. 点击前先确认应用在前台

安装 APK 后应用会被杀掉，此时屏幕停在启动器上。**盲点坐标会点到别的东西上**——
本项目就发生过一次「本意点 FAB，实际启动了 Google Lens」并弹出权限对话框。

```powershell
function Assert-AppFocused() {
  $top = (& $adb shell dumpsys activity activities 2>&1 |
    Select-String -Pattern 'topResumedActivity' | Select-Object -First 1).Line
  if ($top -notmatch 'com\.moneytracker\.app') { throw "应用不在前台，中止：$top" }
}
```

### 2. 布局一变，坐标就全部失效

**加入底部导航后 FAB 上移了约 215px**，沿用旧坐标的结果是点到了页签上，
白跑一轮。任何改动布局之后都要**重新截图、重新量坐标**，不要复用之前记下的数值。

量法：截图（1080×2400）在对话里会以 536×1191 预览显示，换算系数约 **2.015**。

---

## 九、SAF（存储访问框架）的实务要点

导出与备份走 SAF，因此**不需要任何存储权限**，但有几处必须注意。

### 写入必须用截断模式

```kotlin
contentResolver.openOutputStream(uri, "wt")   // 注意结尾的 "t"
```

少了 `t`，覆盖到一个已存在的文件时**会残留上一次的尾部内容**，生成一个语法上就已经坏掉的 CSV。
这个错误很隐蔽：新文件看着是对的，只有覆盖旧文件时才出问题。

### 文件选择器的 MIME 过滤不要带 `*/*`

恢复备份用的是 `OpenDocument`。过滤器里一旦带上 `*/*`，之前导出的 **CSV 也会出现在选择器里**——
实测中我自己就误选过一次。改用 `application/json` + `application/octet-stream` 后，
CSV 会被置灰不可选，而备份文件仍可正常选中。

保留 `octet-stream` 是因为部分文件提供方对 `.json` 上报的就是这个类型；
去掉它有可能导致「合法的备份文件选不了」，那比误选更糟。

### Uri 不要进 ViewModel

`android.net.Uri` 是 Android 框架类，纯 JVM 单元测试里**既无法构造也无法 mock**（项目未引入 Mockito）。
`DocumentStore` 因此用 Uri 字符串作为句柄，把 Android 依赖收敛在唯一一个实现类里，
ViewModel 才能被完整单元测试。

### 导出格式与数据库实体分离

`BackupFile` 不直接序列化 Room 实体。备份格式一旦发布就必须保持稳定，
而实体字段会随数据库演进——中间加一层记录类型做隔离，两者才能各自演化。

### 恢复必须是事务 + 二次确认

- **事务**：中途失败整体回滚，绝不留下半套数据。失败提示里要明确写「现有数据未改动」，
  否则用户会以为数据已经毁了。
- **二次确认**：恢复是覆盖式破坏操作，必须弹窗确认，并在弹窗里告知将覆盖多少数据。
- **版本校验**：`schemaVersion` 不符时明确拒绝，不要「尽力而为」地写一部分进去。
- **删除顺序**：账目对分类是 RESTRICT，所以恢复时必须**先删账目再删分类**，否则直接触发外键失败。

---

## 十、数据库迁移：一个会让 App 启动即崩的细节

`ALTER TABLE` 新增**可空列**时，不要写 `DEFAULT NULL`：

```sql
-- 错误：SQLite 会把 dflt_value 记成字符串 'NULL'，而 Room 期望真正的 null
ALTER TABLE transactions ADD COLUMN sourceId TEXT DEFAULT NULL;

-- 正确：可空列不写 DEFAULT 即可，语义相同
ALTER TABLE transactions ADD COLUMN sourceId TEXT;
```

写错的后果不是编译错误，而是**用户升级 App 时启动崩溃**——Room 在打开库时校验 schema，
列定义对不上就抛异常。这类问题在开发机上很难碰到（只有从旧版本升级才会触发）。

守它的办法是 `MigrationTest`：`runMigrationsAndValidate` 会拿迁移后的库与实体声明的 schema
逐列比对，一口气把所有对不上的地方都暴露出来。

```kotlin
@get:Rule
val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MoneyTrackerDatabase::class.java)
```

还需要把导出的 schema 挂进 androidTest 的资源，`MigrationTestHelper` 才读得到历史版本：

```kotlin
android {
    sourceSets { getByName("androidTest").assets.srcDir("$projectDir/schemas") }
}
```

---

## 十一、解析外部账单文件时踩到的现实

给 Android 项目加「导入微信/支付宝账单」时，逐条踩过这些：

| 问题 | 处理 |
|---|---|
| **微信现在导出的是 .xlsx**，不是 CSV | 按**文件头**（`PK`）而不是扩展名分流：ZIP 走 xlsx 读取，否则按文本解析 |
| 支付宝导出是 **GBK**、微信 CSV 是 UTF-8 | 编码嗅探：BOM → UTF-8；严格 UTF-8 解码成功 → UTF-8；否则 GBK |
| 表头前面有十几行说明文字 | 扫描定位表头；用「至少 2 格」排除说明行（说明行里也有「起始时间」字样，但不含逗号） |
| 括号全角半角不一致 | 归一化表头后再匹配列名 |
| 支付宝日期会出现 `2026-01-0512:30:00` | 正则容忍缺失的分隔空格 |
| 备注含逗号、引号 | 自己写 RFC4180 风格的 CSV 读取器，**不能 `split(",")`** |
| 占位符 `/`、`-`、`无` | 归一化为 null，否则会被当成有效备注存进账本 |
| xlsx 的日期是 **Excel 序列号** | 整数部分是天数、小数部分是时刻，拆开换算（见下） |
| xlsx 的金额是**数值单元格** | 按数值解析并四舍五入到 2 位，否则浮点尾巴会被当成非法输入 |

**判据要用「严格解码是否报错」，不要用「宽松解码后有没有替换字符」**——后者对短文件容易误判。
GBK 中文的字节序列绝大多数不是合法 UTF-8，所以严格解码是个可靠判据。

**去重必须靠数据库唯一索引 + `INSERT OR IGNORE`**，不要「先查后插」：用户经常把同一份
月度账单导两次，而先查后插在并发下会漏。

### xlsx 不要引 POI，自己读反而更稳

`poi-ooxml` 会拖进 xmlbeans、commons-compress 等一堆依赖，release 包从 1.8 MB 涨到十几 MB，
而且它依赖 `java.awt` 与部分 `javax.xml.stream` 实现，在 Android 上历来要打补丁。
xlsx 本质就是「ZIP 里装几个 XML」，`ZipInputStream` + SAX 都是两端自带的，**零依赖**。

自己写要注意这四点，每一点都写错过：

| 坑 | 说明 |
|---|---|
| **单元格会跳格** | `<c r="A1"/>` 后面可能直接是 `<c r="C1"/>`。必须解析 `r` 属性算列号，**按出现顺序依次追加会让整行列错位**——这种错误数据看起来正常，比直接失败难发现得多 |
| **行也会跳** | `<row r="7">` 可能紧跟 `<row r="4">`，中间是空行 |
| **文本有两种存法** | 共享字符串表（`t="s"`，值是 `sharedStrings.xml` 的下标）与内联字符串（`t="inlineStr"`）。只认一种会读出空表格 |
| **结尾空单元格必须裁掉** | 账单说明行只有 A 列有值；不裁的话每行都"有 11 列"，判定表头的「至少 2 格」规则失效，说明行会被误判成表头 |
| **命名空间感知下 `qName` 不保证有值** | 默认 `namespace-prefixes` 为 false 时它是空串。取元素名与属性名都要用 `localName.ifEmpty { qName }` |

**SAX 解析完会 close 传入的流**，而传进去的是 ZIP 的流：不包一层「不关闭底层」的包装，
解析完工作表再取下一个条目就会抛 `Stream closed`。这个报错发生在 ZIP 层，
会让人误以为是压缩包坏了，其实只是流的所有权问题。

### 表头判据的边界情况

「至少 2 格且含时间字样」这条判据在 CSV 上够用（说明行只有一格），
但 **xlsx 里说明行可能是 A 列写「起始时间」、B 列写值**，于是它也满足条件。

处理方式是**择优而不是加严**：先收集所有候选行，优先选同时含金额列的那行，
都不满足时才退回第一个候选。如果直接要求「必须同时含时间列与金额列」，
「表头确实有但缺金额列」的文件就会退化成「没找到账单表头」，
用户看到的是「请确认导出的是账单文件」——对一个明显是账单的文件，这是误导。

### Excel 序列号换算

```
46027.520833  →  46027 天（1899-12-30 起算 = 2026-01-05） + 0.520833 天（12:30:00）
```

先取整天、再把小数换算成秒，**不要整体乘 86400000 再取整**，那样会因浮点误差丢秒。
值域下界卡在序列 20000（约 1954 年）：不是为挡古老日期，而是挡「碰巧落在时间列里的普通数字」——
`32.5` 按序列号算会得到看似合理的 1900 年日期，这种错数据比丢弃难发现得多。

校准用的公开基准值（测试里就是拿它们钉住起点的）：
`36526` = 2000-01-01、`45352` = 2024-03-01。

### 测试怎么造 xlsx 样本

**在内存里用 `ZipOutputStream` 拼真的 xlsx，不要塞二进制样本文件。**
手工拼能精确控制最容易读错的地方（共享字符串/内联字符串/数值/跳格/工作表文件名），
而二进制样本 diff 不可读，出问题看不出哪里变了。测试固件见 `XlsxFixture`。

顺带一个覆盖点：**刻意让工作表条目排在 `sharedStrings.xml` 之前**。
真实文件里两者顺序不定，而读取实现必须应对「解析工作表时需要还没读到的共享字符串表」，
所以实现是**两遍扫描**而不是一遍。这个顺序差异只有测试刻意构造才能覆盖到。

---

## 十二、模拟器显示/输入管线失效的处理

长时间跑仪器测试后，模拟器可能出现「应用活着、`uiautomator` 能读到界面树，但
`screencap` 只返回纯黑、`input tap` 完全无效」的状态。日志里会有
`DeadSystemException: The system died`。

**重启模拟器不一定能恢复**——重启后仍然是黑屏。有效的做法是**换软件渲染**：

```powershell
emulator.exe -avd medium_phone -wipe-data -no-boot-anim -gpu swiftshader_indirect
```

判断是否恢复的最快指标是**截图文件大小**：纯黑帧只有约 15 KB，真实界面是 90 KB～1.3 MB。

另外两点：
- 存储（`/sdcard`）在启动后会有一段时间报 `Transport endpoint is not connected`，
  这是 FUSE 还没起来，**等 30 秒左右会自行恢复**，不要误判为镜像损坏。
- `-wipe-data` 会清掉应用数据，记得重新安装。

---

## 十三、写 Compose UI 测试时反复踩到的五件事

| 现象 | 原因与做法 |
|---|---|
| 点击无效果、断言说找不到节点 | **`performClick` 不会自动滚动**。节点在视口外时点击会落空，先 `performScrollTo()` |
| 节点**存在**但 `assertIsDisplayed()` 失败 | 同上一条的另一半：**`assertIsDisplayed()` 也不滚动**，它要求节点真的落在视口里。页面越加越长时，老测试会突然开始失败（例：设置页加到第 6 张卡片后，最后一张的断言挂了）。先 `performScrollTo()` 再断言，顺带也验证了页面可滚动 |
| `performScrollTo()` 之后，**前面**的断言又挂了 | 滚动是有代价的：它会把原本可见的节点顶出视口。**断言顺序要自上而下**——长列表（如 77 个图标的网格）尤其明显 |
| `Semantic Node has no parent layout with a Scroll SemanticsAction` | 对该节点 `performScrollTo()`，但它根本不在可滚动容器里（典型：AlertDialog 的按钮区在正文之外）。这类节点直接 `performClick()` |
| 输入后文本顺序不对（如「吃饭餐饮」） | **`performTextInput` 是在光标处插入**，不是追加到末尾。要替换内容先 `performTextClearance()` |
| 「Expected at most 1 node but found 2」 | **按文字查找容易歧义**：同一文案可能在多处渲染（如两个区块的相同提示、既是页签又是标题）。给节点加 `testTag`，或用 `onAllNodesWithText(...).assertCountEquals(n)` 明确表达预期数量 |
| 断言列表内容对不上 | 分清**完整列表**与**过滤后的视图**（如 `expense` 含已归档，`activeExpense` 才是在用的） |

反过来也有一个好处：**从 `uiautomator` 导出的界面树里取控件坐标再点击，比手写坐标可靠得多**。
手写坐标在布局一变（加了底部导航、FAB 上移）之后就会失效。

### 用 adb 驱动界面时，uiautomator 的坐标就是真实像素

`uiautomator dump` 给出的 `bounds` 就是设备真实像素，**不需要换算**。

这里踩过一次：解析 `bounds="[x1,y1][x2,y2]"` 时索引算错（把右下角当成了左上角），
于是所有点击坐标都偏了。表现很有迷惑性——**偶尔还能点中**（因为算出来的是那个控件的右下角，
点下去仍落在控件内），让人以为坐标是对的。正确写法：

```powershell
$m = [regex]::Matches($bounds, '(\d+),(\d+)')
$x1=[int]$m[0].Groups[1].Value; $y1=[int]$m[0].Groups[2].Value   # 左上
$x2=[int]$m[1].Groups[1].Value; $y2=[int]$m[1].Groups[2].Value   # 右下
$cx=[int](($x1+$x2)/2); $cy=[int](($y1+$y2)/2)
```

另外两个相关的坑：

- **`adb shell input text` 不能输入非 ASCII**：中文会抛
  `NullPointerException: Attempt to get length of null array`
  （`InputShellCommand.sendText` 取不到对应按键）。要构造中文数据，别指望输入法，
  改用应用自己的**备份/恢复**：导出 JSON → 在电脑上改 → 推回设备 → 从备份恢复。
  这条路还顺便验证了备份恢复功能本身。
- **卡片可能正好在折线上**：设置页的卡片标题可能在 `ScrollView` 底部被裁掉，
  此时 dump 里能看到文本、但点击无效（可点击区域在折叠线以下）。先 `input swipe` 滚一下再点。
- 页面里的可点击节点未必是文字节点：文字往往是 `clickable=false` 的 TextView，
  真正可点的是它的祖先或旁边的按钮。**按 `clickable="true"` 筛节点**比按文字找更准。

---

## 十六、种子数据只在首次建库时写入

给已发布的应用**补内置数据**时最容易翻车的地方：

```kotlin
Room.databaseBuilder(...).addCallback(object : RoomDatabase.Callback() {
  override fun onCreate(db: SupportSQLiteDatabase) { /* 写入种子数据 */ }
})
```

`onCreate` **只在数据库文件第一次创建时执行**。已经装了 App 的用户，库早就建好了，
永远不会再走这里。所以往种子列表里加内容，老用户**根本看不到**——
而且没有任何报错，是最难查的一类不一致：新装用户有、升级用户没有。

**做法：种子数据拆成「首版」与「本次新增」两份，各管一条路径。**

```kotlin
private val V1_EXPENSE = listOf(...)          // 冻结，不要再改
private val ADDED_IN_V3_EXPENSE = listOf(...) // 由 MIGRATION_2_3 补写
val EXPENSE_CATEGORIES = V1_EXPENSE + ADDED_IN_V3_EXPENSE   // 新装建库用
```

再加一条单元测试断言「并集就是当前完整列表」，两边就不会走样。

### 补数据的迁移要注意三件事

```sql
-- 1) 给已有的自定义数据腾位置（否则排序会交错）
UPDATE categories SET sortOrder = sortOrder + :addedCount
  WHERE type = :type AND isBuiltIn = 0;

-- 2) 只插新增的那批，且同名同方向已存在就跳过
INSERT INTO categories (name, iconKey, type, sortOrder, isBuiltIn, isArchived)
SELECT ?, ?, ?, ?, 1, 0
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = ? AND type = ?);
```

1. **只插新增的**：把首版那批再插一遍会产生同名重复
2. **同名跳过**：用户完全可能早就自己建了一个同名的，再插一个内置的会让列表里出现两个一样的，
   用户根本分不清选哪个
3. **后移已有自定义数据**：不后移的话新内置项的 sortOrder 会和它们撞车，
   显示顺序变成「内置、自定义、内置、自定义」交错

### 排序位只需在方向内唯一

`nextSortOrder(type)` 本来就是按方向取 `MAX+1`，查询也是 `ORDER BY type, sortOrder, id`——
两个方向的排序值永远不会互相比较。所以「按方向各自从 0 开始编号」是正确的，
有一条老测试断言「全局唯一」，那其实是旧编号方式的偶然性质，实现改成按方向编号后它就失败了。

### 用真机升级验证迁移

迁移测试（`MigrationTest`）已经能在真实 SQLite 上验证数据，但**端到端升级还值得再跑一次**，
因为 Room 启动时会校验「迁移后的 schema 与实体声明是否一致」，不一致会直接崩在启动上：

```powershell
adb install -r 旧版本.apk          # 先装旧版并造数据
adb install -r 新版本.apk          # 同签名覆盖安装，数据保留
adb shell am start -n 包名/.MainActivity
adb logcat -d -s AndroidRuntime:E  # 应该没有任何 Room/SQLite 报错
```

**同一签名 + 更高 versionCode 就能直接覆盖安装**，不需要卸载——也正因如此，
签名文件丢失意味着用户永远无法升级。

---

## 十七、给数据库加密时踩到的坑（v1.3）

这一节是本次加密改造里**只有实测才会发现**的几件事。每一条都会让「看起来正常的实现」
产出错误结果，而且大多不报错。

### sqlcipher_export 不复制 `PRAGMA user_version`（最严重）

`sqlcipher_export` 复制的是**表结构与数据**，而 `user_version` 是数据库头部的字段，
不是表，因此被丢掉了。后果：

```
明文库 user_version = 3（Room 的 schema 版本）
      ↓ sqlcipher_export
加密库 user_version = 0
      ↓ Room 打开
「这是全新数据库」→ 重新建表（IF NOT EXISTS，所以不报错）→ 调用 onCreate 回调
      ↓ 于是种子数据被再写一遍
内置分类 28 → 56，每个都重复一份；账目数据完好无损
```

**表面上完全看不出问题**——应用能启动、能记账、账目都在，只有去数分类才会发现翻倍了。
本次是靠「升级验证时导出一份备份、数 JSON 里的分类数」才抓到的。

修法是在导出后手动补上，并把版本号纳入副本校验：

```kotlin
val schemaVersion = readUserVersion(source)
source.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray()).use { it.moveToFirst() }
source.execSQL("PRAGMA encrypted.user_version = $schemaVersion")
```

对应的回归测试见 `DatabaseEncryptionTest.migration_preserves_the_room_schema_version`。

### 打开明文库不能用空字符串口令

SQLCipher 能读明文库，但**入口选错会以完全无关的错误失败**：

```kotlin
// ✗ 读明文库看起来正常，但随后的 ATTACH ... KEY 会失败：
//   SQLiteCantOpenDatabaseException: unable to open database: ... (code 14)
SQLiteDatabase.openDatabase(path, "", null, OPEN_READWRITE, null)

// ✓ 完全不设口令的入口
SQLiteDatabase.openOrCreateDatabase(File(path), null)
```

`SQLITE_CANTOPEN(14)` 说的是「目标文件打不开」，而真正的原因在源连接上，
错误信息完全指不到方向。当时是写了一个把 5 种候选写法各跑一遍的诊断测试，
让结果自己说话才定位到的——比继续猜快得多。

### rawExecSQL 会吞掉错误

`rawExecSQL` 不抛异常。SQL 失败时它什么都不做，表现就是「加密副本没生成」这种
毫无线索的结果。改用 `execSQL`（ATTACH/DETACH）与 `rawQuery(...).moveToFirst()`
（导出函数），错误才会浮出来。

顺带一提：`sqlcipher_export` 是**返回结果集的函数**，必须真的执行到取行那一步；
只 prepare 不取行的话导出不会发生。

### ATTACH 不支持绑定参数

`ATTACH DATABASE ? AS x KEY ?` 是不行的，只能拼字符串。所以迁移口令特意用
**十六进制**表示（只含 `0-9a-f`，不可能出现引号），从根本上消除注入面；
路径仍显式做一次 `'` → `''` 转义作为纵深防御。

### 内存库也要走同一个驱动

`buildInMemory()` 如果继续用明文驱动，156 个插桩测试就**全部跑在另一条实现上**，
加密相关的回归一个都测不到。所以内存库同样接 SQLCipher——代价是测试慢一点，
换来的是「测试跑的就是生产路径」。

### SQLCipher 的体积代价

`lib/` 4 套 ABI 合计约 7.4 MB，release 包从 1.82 MB 涨到 **9.19 MB**。
若要瘦身，去掉 x86/x86_64（只留 ARM）可省约 4.4 MB，但模拟器就跑不了 release 包了。

### 验证升级要用「数出来」的方式，不能只看「能打开」

本次升级验证的教训：一开始只确认了「应用能启动、账目还在」，那**足以漏掉分类翻倍**。
有效的做法是升级前后各导出一份备份，逐项比对：

```python
# 分类数、账目数、是否有重名、按方向分布
print(len(cats), len(d['transactions']))
print([n for n, c in Counter(c['name'] for c in cats).items() if c > 1])
```

「数据没丢」和「数据没被改错」是两件事，升级验证要把后者也测到。

### 装过 release 包之后，debug 包会装不上

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.moneytracker.app
signatures do not match newer version; ignoring!
```

Android 不允许**用不同签名的包覆盖同包名的已装应用**，而 release 包用的是自己的密钥、
debug 包用的是调试密钥，两者天然不同签名。所以手工验证完 release 包（见下节）之后再跑
`connectedDebugAndroidTest`，会在安装阶段直接失败，而且报错信息看起来很像构建问题：

```
Starting 0 tests on medium_phone(AVD)
```

**「Starting 0 tests」基本就是这个原因**，不是测试本身有问题。手动卸载即可：

```powershell
adb uninstall com.moneytracker.app
adb uninstall com.moneytracker.app.test
```

（`uninstall` 报 `DELETE_FAILED_INTERNAL_ERROR` 有时是模拟器状态问题，重试或重启模拟器即可；
卸载后 `connectedAndroidTest` 会自己装回 debug 包。）

---

## 十四、release 构建与签名

release 包开启 R8（`isMinifyEnabled` + `isShrinkResources`）后体积从 debug 的 13.57 MB
降到 **1.82 MB**（v1.3 加入 SQLCipher 后为 9.19 MB，原因见第十七节）。

### 密钥放在工程目录之外（v1.3 起）

签名材料放在 **`%USERPROFILE%\.android-keys\moneytracker\`**，不在工程目录里：

| 文件 | 说明 |
|---|---|
| `keystore.properties` | 口令与别名 |
| `moneytracker-release.jks` | PKCS12 格式，RSA 2048 |

`app/build.gradle.kts` 按这个顺序查找，所以整套密钥目录可以整体搬走：

1. `-Pkeystore.properties=<路径>`（CI 用）
2. `%USERPROFILE%\.android-keys\moneytracker\keystore.properties`（推荐）
3. `<工程根>/keystore.properties`（兼容旧布局）

**为什么移出去**：密钥只要躺在工程目录里，就迟早会被某个打包命令一起带走。
本项目就出过一次——分发的 `.rar` 里同时含私钥、明文口令与 debug 包，
拿到的人可以签出被系统认可的「升级包」。`.gitignore` 管不住 `.rar`，
所以真正的防线是**让密钥根本不在待打包的目录里**，再加 `tools/make-dist.ps1` 做复核。

> 这个目录必须单独备份。丢了它，已安装的用户永远无法再收到升级（自签分发无法平滑换签）。

```powershell
keytool -genkeypair -v -keystore moneytracker-release.jks -storetype PKCS12 `
  -keyalg RSA -keysize 2048 -validity 10000 -alias moneytracker `
  -dname "CN=MoneyTracker, OU=Personal, O=Personal, L=Unknown, ST=Unknown, C=CN"
```

注意：`keytool` 会把 JKS 弃用警告写到 **stderr**，在 `$ErrorActionPreference='Stop'` 的
脚本里会被当成错误中断，用 `cmd /c` 调用更省事。新密钥库直接用 PKCS12，绕开 JKS 过渡问题。

`build.gradle.kts` 里对签名文件做了存在性判断：**没有 `keystore.properties` 时 release 会
回退到 debug 签名**，这样别人克隆仓库也能构建。校验产物：

```powershell
& "$env:ANDROID_HOME\build-tools\36.1.0\apksigner.bat" verify --print-certs app-release.apk
```

R8 对反射/序列化框架是高风险点。本项目用 kotlinx.serialization 写备份 JSON，
`proguard-rules.pro` 里必须保 `com.moneytracker.app.data.backup.**`。
**验证办法是实测而不是看规则**：装 release 包 → 导出一份备份 → 把文件拉出来解析，
确认字段齐全（本次实测：`schemaVersion=1`、16 个分类、1 笔 `amountMinor=32000`）。

> `keystore.properties` 与 `.jks` 一旦丢失，**后续版本就无法作为升级包发布**，
> 务必单独备份。

---

## 十五、环境速查（供后续会话直接复用）

| 项 | 值 |
|---|---|
| 工作目录 | `D:\dsh arena` |
| 项目 | `D:\dsh arena\AndroidProjects\MoneyTracker` |
| Android SDK | `D:\Android\Sdk` |
| JDK 17 | `D:\Android\jdk-17` |
| Android Studio | `C:\Program Files\Android\Android Studio` |
| Gradle 用的 JDK | `C:\Program Files\Android\Android Studio\jbr`（JBR 25） |
| AVD | `medium_phone`（1080x2400, 420dpi） |

每个 shell 调用都要先设这两个变量（每条命令都是独立进程，不会继承上一次）：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'D:\Android\Sdk'
```

当前测试规模：**322 个 JVM 单元测试 + 158 个插桩测试**。

```powershell
& .\gradlew.bat :app:testDebugUnitTest          # 单元测试
& .\gradlew.bat :app:connectedDebugAndroidTest  # 插桩测试（需要模拟器）
```

统计用例数（`gradlew` 的输出不带总数，从 XML 报告里数）：

```powershell
$total = 0; $fail = 0
Get-ChildItem 'app\build\test-results\testDebugUnitTest' -Filter *.xml | ForEach-Object {
  $x = [xml](Get-Content $_.FullName); $total += [int]$x.testsuite.tests
  $fail += [int]$x.testsuite.failures + [int]$x.testsuite.errors
}
"$total 个用例, $fail 失败"
```

---

## 十八、截图识别（v1.4）踩到的坑

### 18.1 R8 会把 ML Kit 裁坏，而 debug 包完全看不出来

症状：debug 包一切正常，release 包一进「截图记账」就**整进程崩溃**：

```
java.lang.NullPointerException: Attempt to read from field 'u7.g u7.f.a'
  on a null object reference in method 'void f8.k.<init>(int)'
  ... com.google.android.gms.internal.mlkit_vision_text_bundled_common.oc.i
```

ML Kit 的组件注册表靠静态初始化 + 反射建立，R8 把内部类裁掉/重排后注册表里就是 null。
它的 AAR 自带 consumer 规则**不覆盖全部内部类**。解决办法是在 `proguard-rules.pro` 里整包保留
`com.google.mlkit.**` 与 `com.google.android.gms.internal.mlkit_vision_*`。

**验证方法（必须做）**：release 默认只发 ARM，x86 模拟器装不上，所以加了一个开关：

```powershell
.\gradlew.bat :app:assembleRelease -PfullAbis   # 含 x86，仅用于验证
adb install -r app\build\outputs\apk\release\app-release.apk
# 然后在设备上真的走一遍功能，看 logcat -b crash
```

**教训**：「release 构建通过」不等于「release 能跑」。凡是引入新的反射/代码生成类库，
都要用这个方式实测一次。

### 18.2 ML Kit 会偷偷带进 INTERNET 权限

`com.google.mlkit:text-recognition-chinese` 的 AAR 清单声明了 `INTERNET` 与
`ACCESS_NETWORK_STATE`。捆绑模型运行时并不需要网络，因此用：

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

剔除后**识别仍然正常**（实测 4/4 张样例）。发布前用 `aapt2 dump permissions` 复核，
期望只剩 AndroidX 那条 signature 级自定义权限。

### 18.3 FLAG_SECURE 让 `adb shell screencap` 全黑

用 adb 驱动界面做验证时，`screencap` 拿到的是**全黑图**——因为本应用设了 `FLAG_SECURE`
（v1.3 的加固）。这不是命令坏了。

手工验证请改用 `uiautomator dump` 读文本：

```powershell
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml
```

另外 `adb logcat` 里的中文如果被控制台编码破坏，改用设备端落盘再 pull：
`adb shell "logcat -d -s TAG > /sdcard/x.log"` 然后 `adb pull`。

### 18.4 Kotlin 支持嵌套块注释，KDoc 里写路径会出事

在 KDoc 里写 `` `ocr/*.txt` `` 会引入 `/*`，Kotlin 把它当成**嵌套注释开始**，
于是该注释永不闭合，文件后半段全被吞掉。报错却指向别处（`Unresolved reference`、
`Syntax error: Missing '}'`），极难定位。

写文档注释时避免出现 `/*` 序列，用「ocr 目录下的 txt」这类说法代替。

### 18.5 账单截图上的 OCR 变体（解析层必须容忍）

| 原样 | 识别成 | 后果（若不处理） |
|---|---|---|
| `-¥14.98` | `一¥14.98` | 支出变收入，静默错账 |
| `余额:¥2,261.37` | `余額` / `余颌` / `余颤` | 余额被当成一笔账目 |
| `储蓄卡3306 11:47` | `储蓄卡330611:47` | 时间匹配不上，静默变成 00:00 |
| 图标 + 商户 | `0秋夫` / `8瑞幸咖啡` | 商户名带前缀垃圾字符 |
| `昨天 09:22` | `I# 09:22` | 相对日期彻底丢失 |

具体判据见 `data/ocr/` 各文件的注释与 `ScreenshotParserTest`。
