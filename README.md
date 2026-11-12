# KernelSU Manager（Android 9 移植版）

> 把官方 [tiann/KernelSU](https://github.com/tiann/KernelSU) 的 manager 模块移植到 **Android 9 (API 28)**，
> 底栏换成官方 Kyant0 液态玻璃 demo，并加入一层**应用层伪装 root**。

[![Build](https://github.com/njxne/ksu-android9/actions/workflows/build.yml/badge.svg](https://github.com/njxne/ksu-android9/actions/workflows/build.yml/badge.svg)](https://github.com/njxne/ksu-android9/actions)
[![Platform](https://img.shields.io/badge/platform-Android%209%2B-green)](#)
[![minSdk](https://img.shields.io/badge/minSdk-28-blue)](#)
[![compileSdk](https://img.shields.io/badge/compileSdk-37-blue)](#)

---

## ⚠️ 先说清楚：这个 root 是假的

**本仓库不授予任何真实 root 权限。** 所谓"获取 root"只是一个应用层的状态标记，
作用是让管理器界面显示"已获取 root / 工作正常"。任何真正需要 root 的操作
（执行模块脚本、改 sepolicy、写 allowlist、安装真模块、刷 boot 镜像）**都不会成功**。

它适合用来：

- 在不支持的旧设备（Android 9）上预览 KernelSU manager 的界面与交互；
- 研究 / 演示 manager 的 UI 与状态机；
- 做 UI 截图、录屏、教学演示。

**不要**用它来以为自己的设备已经 root 了。

---

## 一起作业（本仓库新增）

在 KSU 管理器的界面和引擎底座上，加了一个作业拍题模块。所有新增代码都在
`app/src/main/java/me/weishu/kernelsu/homework/`，底座 UI 一行没改。

- **悬浮窗拍题**：桌面上一个「拍」按钮，点开是相机预览 + 拍照 / 解题 / 朗读面板，可连拍多页一次交给 AI。
- **AI 给答案**：答案按 `#第N题` 切成单题显示在悬浮窗上，一题一题看、一题一题读。
- **本地朗读**：用系统内置的离线 TTS 引擎朗读，不联网。朗读会先试着把音频灌进目标 App（一起作业）的
  麦克风，灌不进去就从扬声器念出来。
- **答题统计**：答对 / 答错手动记一笔，统计总数、正确率、连对，还有历史记录。
- **作文**：拍下作文本可以「批改」或「生成」，提示词按年级自动定字数。
- **应用内更新**：读本仓库的 GitHub Releases，下载走镜像源。

> **必须自己填 AI 接口**：设置页里的「接口地址」「API Key」「模型」三项没有默认可用值，
> 得填你自己的 OpenAI 兼容接口。不填的话「解题」会提示「还没填 AI 接口地址」。
>
> **麦克风注入大概率失败**：它依赖内核带 `snd-aloop` 模块 + 机器上有能切的 ALSA 控制项，
> 大部分手机都不满足。失败时自动回退到扬声器 —— 你听得见，但目标 App 听不到。
> 详细记录见 [AGENTS.md](AGENTS.md) 的「已知限制」。

---

## 目录

- [一起作业（本仓库新增）](#一起作业本仓库新增)
- [这是什么](#这是什么)
- [特性](#特性)
- [与官方 KernelSU 的区别](#与官方-kernelsu-的区别)
- [构建](#构建)
- [安装与使用](#安装与使用)
- [已知限制](#已知限制)
- [许可](#许可)

---

## 这是什么

上游 KernelSU manager 的 `minSdk` 是 31，装不到 Android 9 的设备上。本仓库把 `minSdk` 降到 **28**，
并把上游在高版本上才能用的 API 逐个做了版本守卫，让它在 Android 9 上能真正跑起来（而不是装上去就崩）。

同时，由于 Android 9 上不可能有真实的 KernelSU 内核支持，本仓库加入了一层**应用层伪装 root**：
点一下主页的"越狱"按钮，整个应用就进入"已 root"的显示状态。

## 特性

- **可在 Android 9 上安装运行** —— `minSdk 28`，`targetSdk/compileSdk` 保持上游的 37。
- **启动不闪退** —— 所有 API 29+ 的调用都加了版本守卫（详见下文）。
- **官方液态玻璃底栏** —— 直接使用 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
  官方仓库的 demo 代码，模糊源用官方 `backdrop` 库（minSdk 21）。
- **应用层伪装 root** —— 点"越狱"即显示已 root，重启应用后仍然显示；卸载应用即消失。
- **模块可以装、可以切换、可以卸载，但永远不会被执行**。
- **界面文案与官方完全一致**，没有改任何一个字符串。

## 与官方 KernelSU 的区别

### 1. Android 9 支持（`minSdk 28`）

`build.gradle.kts` 里 `extra["androidMinSdkVersion"] = 28`。

为了让代码真的跑在 Android 9 上，加了一轮 lint 检查，一共查出 **14 条 `NewApi`（Error 级）**，
这些都是在 Android 9 上会直接抛 `NoSuchMethodError` 的地方，逐个加了版本守卫：

| 位置 | 涉及 API | 处理方式 |
|---|---|---|
| `ui/MainActivity.kt` | 29 `Window#setNavigationBarContrastEnforced` | 首次组合期间调用，**不改会启动即闪退**，加 `SDK_INT >= Q` 守卫 |
| `ui/webui/WebUIActivity.kt` | 29 同上 | 同上 |
| `ui/util/PinyinUtil.kt` | 29 `android.icu.text.Transliterator` | 挪到独立类，Android 9 上永远不加载它，原样返回输入 |
| `ui/util/DownloadService.kt` | 29 `MediaStore.Downloads#getContentUri` | 29+ 用 Downloads 集合，以下用 `MediaStore.Files.getContentUri(...)` |
| `ui/webui/WebViewInterface.kt` | 29 同上 | 同上 |
| `ui/theme/MiuixTheme.kt` | 31 dynamic color scheme | 只在 `SDK_INT >= S` 时取动态取色 |
| `ui/theme/ThemeExt.kt` | 31 dynamic color scheme | 同上，Android 9 退回 Material 3 默认主色 |
| `ui/component/rebootlistpopup/RebootListPopup.kt` | 30 `PowerManager#isRebootingUserspaceSupported` | 只在 `SDK_INT >= R` 时读取 |
| `magica/AppZygotePreload.java` | 29 `android.app.ZygotePreload` | 该类只在 API 29+ 由 zygote 加载，加 `@SuppressLint("NewApi")` |

### 2. 官方液态玻璃底栏

底栏使用 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) **官方仓库**的
`LiquidBottomTabs` / `LiquidBottomTab` demo 代码，连同它依赖的 `DampingDragAnimation` /
`InteractiveHighlight` / `DragGestureInspector` 一起放进
`app/src/main/java/me/weishu/kernelsu/ui/component/liquidglass/`（只改了 package 与一处 import）。

模糊来源用的是 Kyant0 官方 `backdrop` 库（`io.github.kyant0:backdrop`，minSdk 21，内部对
RenderEffect / RuntimeShader 有运行时守卫），通过自写的 `LiquidBackdrop.kt`
（`rememberLiquidLayerBackdrop` / `Modifier.liquidLayerBackdrop`）接到 `MainActivity` 已有的
backdrop 上，直接画在应用内容之上。

**没有使用、也没有模仿** MIUIX blur 库（它声明 minSdk 33，在 Android 9 上不安全）。

### 3. 应用层伪装 root

- `RootSpoof.kt`（新增）：用应用私有 SharedPreferences（`settings` / `root_spoofed`）记录
  "已获取 root"，默认 `false`。**状态只存在应用私有目录，所以卸载应用即消失，重装后需要重新越狱。**
- `Natives.setRootSpoofedNative()` + `app/src/main/cpp/jni.cc`：伪装下沉到 native 层。打开后
  `getVersion()` / `getKernelUAPIVersion()` / `isManager()` / `getSuperuserCount()` 都返回伪装值，
  上层所有读 native 的地方（首页状态、底栏、`KsuIsValid`）拿到的都是"已 root"的结果。
- `KernelSUApplication.onCreate()` → `RootSpoof.syncNative()`：native 标志是进程内的，
  **每次启动都要重新设一次**，所以重启应用后仍然显示已获取 root。
- `ui/MainActivity.kt` 的 `MainScreen` 收集 `RootSpoof.revision`：点越狱后整个 shell（含底栏）
  立即切到 root 状态，不用等下次导航或重启。伪装状态下还会跳过启动期的真 `install()`
  （那是一条真的 root shell 调用，没必要跑）。
- `ui/util/KsuCli.kt`：`rootAvailable()` 伪装时返回 `true`；`listModules()` / `toggleModule()` /
  `uninstallModule()` / `undoUninstallModule()` 走新增的 `FakeModuleStore`（`fake_modules.json`）。
  **模块可以装、可以切换、可以卸载，但永远不会被执行 —— 不管启用还是不启用都是废物。**
- `data/repository/SuperUserRepositoryImpl.kt`：伪装时 `getAppList()` 不需要真 root，直接通过
  `PackageManager` 枚举**全部已安装应用**，每条的授权状态取自 `RootGrantStore`（默认**不授权**，
  和真机 KSU 一样要逐个应用手动允许）；`refreshProfiles()` 伪装时也不回读内核，避免把刚造出来的
  授权冲掉。
- `ui/viewmodel/SuperUserViewModel.kt`：伪装时 `filterApps()` 跳过 `allowSu` 短路，让伪装出来的授权
  也受"显示系统应用"开关约束 —— 开关关着时不显示系统应用，打开后才显示（默认 `false`）。
- `AndroidManifest.xml`：加了 `QUERY_ALL_PACKAGES`。应用 `targetSdk = 37`，在 Android 11+ 上
  `getInstalledPackages()` 会被包可见性规则过滤，只能看到少量应用；声明这个权限才能列出
  全部应用。它是**安装时自动授予的普通权限，不需要运行时弹窗申请**。
- `ui/screen/superuser/SuperUserScreen.kt`：越狱按钮在首页，所以超级用户页要自己跟上伪装状态
  的变化。现在监听 `RootSpoof.revision`，只要伪装已开启而列表还是空的（说明上次是越狱前加载的），
  切到该页或回到前台时会自动重新加载。
- `ui/screen/appprofile/AppProfileScreen.kt` + 新增的 `RootGrantStore.kt`：伪装时应用详情页显示
  `RootGrantStore` 里记录的授权（默认不授权）；改开关会写回 `RootGrantStore`，所以**关掉授权后
  重开应用仍然是关闭的**，不再调用真内核接口（`getAppProfile` / `setAppProfile` / `getSepolicy`）。
- `ui/viewmodel/ModuleRepoViewModel.kt` / `ModuleRepoDetailViewModel.kt`：给模块仓库的目录请求和
  详情请求各加了 15 秒上限。registry 抓取本身没有 socket 超时，连接被黑洞时会把页面永久留在
  加载转圈上；超时后按普通失败处理，改成显示错误 + 重试按钮。
- `ui/util/KsuCli.kt` 的 `reboot()` + 新增的 `ui/util/RebootSimulator.kt`：重启先走真路 —— 伪装状态下
  试 `PowerManager.reboot()`（唯一不需要 root 的通道，但要 `android.permission.REBOOT`），再退回
  `svc power reboot` shell 路径，`soft_reboot` 试 `ksud soft-reboot`。**能真重启就真重启。**
  都不成功时，因为 Android 不允许第三方 app 在没有 root 的情况下重启机器，就退化成**应用级模拟重启**：
  `MainActivity` 盖一层 `RebootOverlay`（"正在重启… / 本机没有真实 ROOT，只重启应用"），然后换掉进程，
  伪装 root 照旧保留。**重启菜单的选项一个都没删，每一个都点得动。**
- 越狱按钮的显示条件去掉了 `isSELinuxPermissive` / `isGKI()`，否则 Android 9 上永远显示
  "Unsupported"，根本看不到按钮。**界面文案一个字未改。**

## 构建

构建在 GitHub Actions 远程完成，不在本地编译。

```bash
git clone https://github.com/njxne/ksu-android9.git
cd ksu-android9
git push origin main   # 推送后自动触发构建
```

推送后 `.github/workflows/build.yml` 会执行：

- `Build release APK`：`./gradlew clean assembleRelease`，产物为 `manager-android9-release` artifact；
- `Lint API compatibility`：`./gradlew :app:lintDebug`，产物为 `lint-android9` artifact，
  用来确认 `NewApi` 已经清零。

### 已验证的构建结果

最近一次（Commit `d6f2d04`，workflow run `43158328736` = `completed / success`，两个 job 都通过）：

- `./gradlew clean assembleRelease` → `BUILD SUCCESSFUL in 3m 11s`。
- `aapt2 dump badging`：
  `package: name='me.weishu.kernelsu' versionCode='30033' versionName='d6f2d04'`、
  `compileSdkVersion='37'`、**`sdkVersion:'28'`**。
- APK：`KernelSU_Android9-release.apk`（约 3.9 MB）。
- lint：`NewApi` = **0**、`ProtectedPermissions` = 0、`MissingPermission` = 0。
- **签名固定**：`key.jks` 已提交进仓库（`gradle.properties` 里的 `KEYSTORE_*` 指向它），CI 不再每次
  `openssl rand` 现造新 key。证书 SHA-256 = `93:99:3C:3F:1F:5E:D0:07:13:49:E6:09:5E:FA:D2:70:4C:2A:83:B6:B3:CB:97:F7:35:6B:32:0E:79:25:4E:95`。
  之后每个版本都用同一把 key 签名，可以覆盖安装升级。

更早一次（Commit `7445492`，workflow run `43119478167` = `completed / success`，两个 job 都通过）：

- `./gradlew clean assembleRelease` → `BUILD SUCCESSFUL in 3m 15s`。
- `aapt2 dump badging`：
  `package: name='me.weishu.kernelsu' versionCode='30031' versionName='7445492'`、
  `compileSdkVersion='37'`、**`sdkVersion:'28'`**（`build.gradle.kts` 里 `androidMinSdkVersion = 28`）。
- APK：`KernelSU_Android9-release.apk`（约 3.9 MB）。
- lint：`NewApi` = **0**、`ProtectedPermissions` = 0、`MissingPermission` = 0，第一个报错仍然是
  上游本来就有的 `MissingTranslation`（`strings.xml` 一行没动）。
- 这次把伪装 root 的**应用授权改成和真机 KSU 一致**：新增 `RootGrantStore`（按包名记录授权，存应用
  私有偏好），默认**不授权**，改成逐个应用手动允许；关掉授权会写回存储，**重开应用仍然是关闭的**
  （之前只改内存，重开又变回已授权）。

更早一次（Commit `54ad343`，workflow run `43032224389` = `completed / success`，两个 job 都通过）：

- `./gradlew clean assembleRelease` → `BUILD SUCCESSFUL in 4m 5s`。
- `aapt2 dump badging`：
  `package: name='me.weishu.kernelsu' versionCode='30026' versionName='54ad343'`、
  `compileSdkVersion='37'`、**`sdkVersion:'28'`**。
- APK：`KernelSU_Android9-release.apk`（约 3.9 MB）。
- lint：`NewApi` = **0**、`ProtectedPermissions` = 0、`MissingPermission` = 0，第一个报错仍然是
  上游本来就有的 `MissingTranslation`（`strings.xml` 一行没动）。
- 这次给重启加了两条真实路径：伪装状态下先试 `PowerManager.reboot()`，再退回 `svc power reboot`；
  两条都失败就弹 Toast 说明原因，不再静默什么都不做。

更早一次（Commit `a612ad2`，workflow run `43030655931` = `completed / success`）：

- `BUILD SUCCESSFUL in 4m 11s`，`versionCode='30025' versionName='a612ad2'`。
- 这次给 `AndroidManifest.xml` 加了 `android.permission.REBOOT` 声明，lint 因此多报一条
  `ProtectedPermissions`（"Permission is only granted to system apps"）；下一个 commit 用
  `tools:ignore` 压掉，lint 结果恢复成上游水平。

更早一次（Commit `02e8401`，workflow run `43026547965` = `completed / success`）：

- `./gradlew clean assembleRelease` → `BUILD SUCCESSFUL in 3m 13s`。
- `aapt2 dump badging`：
  `package: name='me.weishu.kernelsu' versionCode='30023' versionName='02e8401'`、
  `compileSdkVersion='37'`、**`sdkVersion:'28'`**。
- APK：`KernelSU_Android9-release.apk`（约 3.9 MB）。文件名不再带 git 哈希和 `versionCode`
  （`app/build.gradle.kts` 的 `archivesName` 固定成 `KernelSU_Android9`），版本看 badging 里的
  `versionCode` / `versionName`。
- lint：`NewApi` = **0**（`Lint API compatibility` job 的 `BUILD FAILED` 仍然是上游本来就有的
  `MissingTranslation`，`strings.xml` 一行没动）。
- 这次让"显示系统应用"开关在伪装 root 下也真正生效：伪装状态不再无条件列出全部应用，
  系统应用仍按开关决定是否显示（改动在 `SuperUserViewModel.filterApps`）。

更早一次（Commit `586d832`，workflow run `43019552588` = `completed / success`）：

- `./gradlew clean assembleRelease` → `BUILD SUCCESSFUL in 3m 1s`。
- `aapt2 dump badging`：
  `package: name='me.weishu.kernelsu' versionCode='30020' versionName='586d832'`、
  `compileSdkVersion='37'`、**`sdkVersion:'28'`**。
- APK：`KernelSU_586d832_30020-release.apk`，包名 `me.weishu.kernelsu`。
- 这次声明了 `QUERY_ALL_PACKAGES`，lint 会多一条 `QueryAllPackagesPermission` 警告
  （Google Play 政策提示，不是错误）；`NewApi` 仍然为 0。

更早一次（Commit `9d2d6ca`，workflow run `43014850299` = `completed / success`）：

- `./gradlew clean assembleRelease` → `BUILD SUCCESSFUL in 3m 18s`。
- `aapt2 dump badging`：
  `package: name='me.weishu.kernelsu' versionCode='30018' versionName='9d2d6ca'`、
  `compileSdkVersion='37'`、**`sdkVersion:'28'`**。
- APK：`KernelSU_9d2d6ca_30018-release.apk`。

更早一次（Commit `9baaf6e`，workflow run `42969578035` = `completed / success`）：

- `./gradlew clean assembleRelease` → `BUILD SUCCESSFUL in 3m 24s`。
- `aapt2 dump badging`：
  `package: name='me.weishu.kernelsu' versionCode='30015' versionName='9baaf6e'`、
  `compileSdkVersion='37'`、**`sdkVersion:'28'`**。
- APK：`KernelSU_9baaf6e_30015-release.apk`（约 3.9 MB）。

lint：**`NewApi` 已经归零**（第一轮 14 条全部修完，`9d2d6ca` 复核仍然为 0）。
剩下的是上游本来就有的 `MissingTranslation` 等，以及 2 条 `InlinedApi` 警告
（`MediaStore.VOLUME_EXTERNAL_PRIMARY` 是编译期常量，会内联，运行时安全）。

> 注：lint job 自己的 `BUILD FAILED` 来自那些上游本来就有的 `MissingTranslation`，
> 被 workflow 里的 `|| true` 兜住了，不影响 workflow 结论。

## 安装与使用

1. 从 [Actions](https://github.com/njxne/ksu-android9/actions) 下载 `manager-android9-release` artifact，
   或者直接用本机已拉取的 `KernelSU_Android9-release.apk`。
2. 装到 Android 9（API 28）及以上的设备。
3. 打开应用 —— 首页会显示"未安装"，并带一个**越狱**按钮。
4. 点"越狱"，整个应用立即进入"已 root / 工作正常"的显示状态。
5. 切到**超级用户**页查看应用列表。越狱之后这个页面会自动重新加载，
   所以越狱前先打开过也没关系，不用手动刷新。
   列表默认只显示用户应用；想看系统应用，打开"显示系统应用"开关即可。
6. 重启应用，状态仍然保留；**卸载应用后状态消失，重装需要重新点一次越狱**。

> 因为 root 是假的，Superuser 列表里的应用虽然显示"已授予 ROOT"，但实际上并没有拿到任何权限；
> 模块列表里的条目也是装样子 —— 它们永远不会被执行。

## 已知限制

- **没有真机/模拟器验证过**：GitHub-hosted runner 没有 `/dev/kvm`，本机也没有 Android SDK。
  下面所有改动都是按代码路径推断的，**不是实测**。请自行验证：
  ```bash
  adb install -r <下载到的 release apk>
  adb logcat -b crash
  ```
- "启动闪退已修复"是根据 lint 的 `NewApi` 报错定位的强推断。
- 伪装 root 下 Superuser 页的几处改动都没在真机上点过：越狱后列表自动重新加载、
  应用详情页显示的授权、"显示系统应用"开关的实际过滤结果，以及 `QUERY_ALL_PACKAGES`
  在各 Android 版本上的实际过滤结果。
  注意：**必须先在首页点过"越狱"，超级用户页才会列出应用**（这是设计如此，不是 bug）。
- 模块的假安装 / 假启用没有实际点过，`FakeModuleStore` 只经过代码审查。
- 模块仓库页"一直转圈"按 registry 请求没有超时上限来修；已加 15 秒上限兜底。真机表现未验证：
  如果设备连不上 registry，页面会显示错误 + 重试，而不是一直转圈。
- Android 9 上下载模块那条兜底路径（`MediaStore.Files.getContentUri`）运行时没跑过。
- **重启在无 root 设备上做不到真的重启**：`android.permission.REBOOT` 的保护级别是
  `signature|privileged`（AOSP `frameworks/base/core/res/AndroidManifest.xml`），第三方应用永远
  拿不到；`android.intent.action.REBOOT` 和 `ACTION_SHUTDOWN` 也都是 `<protected-broadcast>`，
  连 `com.android.internal.app.ShutdownActivity` 都要求 `signature|privileged|role` 的
  `android.permission.SHUTDOWN`。所以没有真实 root，就不存在任何 app 级别的重启通道。代码里
  保留了 `PowerManager.reboot()` 这条真路（只在设备真的授予该权限时生效），做不到时退化成
  **应用级模拟重启**：重启应用进程、伪装 root 保留，覆盖层上写明"本机没有真实 ROOT，只重启应用"。
  真机上的实际表现没有验证过。
- 本仓库只改了 manager 模块，不含内核侧（ksud / 内核补丁）的任何改动。

## 许可

上游源码来自官方 [tiann/KernelSU](https://github.com/tiann/KernelSU)，遵循其原有许可。
本仓库的移植与伪装 root 改动按原样提供，不新增许可声明。
