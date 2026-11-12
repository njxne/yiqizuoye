# AGENTS.md — 一起作业（YiQiZuoYe）

基于 [KernelSU manager（Android 9 移植版）](https://github.com/njxne/ksu-android9) 的「KSU 同款引擎 / 界面」底座，
扩展一个作业拍题 App：**悬浮窗拍照 → 发给 AI → 答案显示在悬浮窗 → 本地朗读 → 统计答对题数 → 应用内更新**。

> 底座直接用现有仓库的代码，不仿写 UI。新增功能全部放在 `app/src/main/java/me/weishu/kernelsu/homework/` 下。

## 构建

```bash
./gradlew :app:assembleRelease        # 产物 app/build/outputs/apk/release/*.apk
```

本机是 aarch64 Termux，AGP 自带 aapt2 是 x86_64，跑不了 —— 本地只能静态检查，构建走
GitHub Actions（`.github/workflows/build.yml`，push 到 main 自动触发，JDK 21 + ubuntu x86_64）。
改完代码后本地无法验证编译，靠 CI。

CI 每次在 main 上构建成功后会**自动发一个 GitHub Release**（APK 作为资产，tag = `v<versionCode>`）。
App 里的「检查更新」读 `repos/{repo}/releases/latest`，没有 Release 就一直是「检查失败」，所以这步不能省。

## 版本契约（别随便改）

- AGP 9.4.1 / Kotlin 2.4.20 / Compose 1.12.0，见 `gradle/libs.versions.toml`。
- **AGP 9 起内置 Kotlin 支持，不要再加 `org.jetbrains.kotlin.android` 插件**，只保留
  `com.android.*` + `org.jetbrains.kotlin.plugin.compose` + serialization + protobuf + apksign。
- minSdk = 28（Android 9+），targetSdk / compileSdk = 37。CameraX 用 1.4.x。
- Release 开了 R8（`isMinifyEnabled = true`），混淆规则只有 `app/proguard-rules.pro` 里那 5 行；
  Compose 和 kotlinx.serialization 靠各自的 consumer rules 保命，**新增用到反射/反射式查找的地方要自己加 keep**。

## 代码结构

- 底座 UI 壳（`ui/`、`ui/theme/`、`ui/component/`、`ui/navigation3/`）：KSU 管理器原样保留，
  双主题（Miuix / Material）、液态玻璃底栏、路由都在。
- 新增作业功能：
  - `homework/Prompt.kt` — 拍题 / 批改作文 / 生成作文提示词（按年级 → 学段分支，自动定字数）
  - `homework/HomeworkSolver.kt` — 拍题 → AI 的链路，含 `splitAnswer()` 按 `#第N题` 切题
  - `homework/ImageUtil.kt` — 图片解码 / 压缩 / 缩略图（长边 2048、质量自适应）
  - `homework/AiClient.kt` — OpenAI 兼容 chat/completions + models 客户端（OkHttp）
  - `homework/HomeworkStore.kt` — 设置 + 答题统计 + 历史（SharedPreferences / JSON）
  - `homework/TtsEngine.kt` — 本地语音朗读（TextToSpeech）
  - `homework/AnswerReader.kt` — 朗读的统一出口：合成 → 试灌麦克风 → 回退扬声器
  - `homework/MicInjector.kt` — 朗读音频注入「一起作业」麦克风（root ALSA loopback，回退扬声器）
  - `homework/UpdateChecker.kt` — 应用内更新：GitHub Releases + 镜像源下载 + 安装
  - `homework/FloatingWindowService.kt` — 悬浮窗服务：拍照 / 答案显示 / 朗读 / 统计
  - `homework/FloatingHomeworkView.kt` — 悬浮窗 View（浮动按钮 + 面板 + 相机预览，纯代码搭界面）
  - `homework/CameraActivity.kt` — 自建相机（CameraX，带闪光灯），产出 content Uri
  - `homework/HomeworkScreen.kt` — 主界面入口（作业卡片 → 详情页：设置/统计/拍题/作文/朗读/更新/历史）
  - `homework/HomeworkEntryCard.kt` — 首页的作业入口卡片
- 入口：`ui/screen/home/` 增加作业卡片；`Routes.kt` 增加 `Route.Homework`。

## 自派任务（全部完成才算交付）

1. 悬浮窗服务 + 相机拍照 + AI 答案显示在悬浮窗 ✅
2. 本地 TTS 朗读 + 朗读按钮（AI 给答案后出现，点击朗读并尝试注入「一起作业」麦克风）✅
3. 答题正确统计（App 内计数，保存本地）✅
4. 应用内更新（GitHub 仓库 + 镜像源下载）✅
5. 推送到 GitHub 触发 CI 远程编译，下载产物验证 ✅
6. 体验官走查：以用户视角体验，找出影响体验的问题，给出建议并更新 ✅（第一轮已做，见下）

## 体验官走查记录（第一轮，已修）

按用户视角把 App 走了一遍，找出这些**真会让人用不下去**的问题，都已改掉：

1. **悬浮窗所有按钮点不动**（最致命）。原来给整个 View 挂了 `setOnTouchListener` 且恒返回 `true`，
   子 View 永远收不到触摸事件，面板里的「拍照 / 解题 / 朗读 / 答对了」全是死的。
   改成 `onInterceptTouchEvent` 拦截模型：没有超过 4dp 的拖动就放行给子 View。
2. **悬浮窗提示看不见**。`hintView` 声明了却从来没 `addView` 进面板，所有 `setHint(...)` 都是空操作。
3. **面板写死 310×600dp**，小屏上会盖满整个屏幕。改成按屏幕尺寸自适应并留出空档。
4. **主页的「朗读」只走扬声器**，悬浮窗才会尝试灌麦克风。用户点完发现「一起作业」没声音，
   会以为功能坏了。抽出 `AnswerReader`，两处统一。
5. **答案不切题**。提示词要求模型每题前面写 `#第N题`，但客户端不切，悬浮窗上就是一堆标记行，
   朗读还会把整份作业一口气念完。加了 `splitAnswer()`，逐题显示 + 「第 X/N 题」切换。
6. **目标 App 包名是猜的**（`com.yiqizuoye`）。加了「从已装应用里挑」，直接从
   `getInstalledApplications` 里选，不再靠猜。
7. **相机权限缺失时只丢一句提示**，没有入口。悬浮窗里加了「去授权相机」跳到系统权限页；
   主页加了「去开权限」跳悬浮窗权限页。
8. **本地语音引擎有没有用，用户无从得知**。等 TTS 初始化完直接显示「可用 / 没找到中文语音数据」。
9. **首页计数卡片缓存了首次结果**，答对题数变了卡片不跟着变。
10. `Prompt.gradeEssay / writeEssay` 原本是死代码，已接成「作文」卡片（批改 / 生成）。

## 已知限制（别假装能跑）

- **AI 接口地址和 Key 必须用户自己填**。`AiClient.DEFAULT_BASE` / `DEFAULT_MODEL` 只是占位默认值，
  不保证可用；不填的话「解题」会提示「还没填 AI 接口地址」，设置页里能改。
- **麦克风注入大概率失败**。它依赖内核带 `snd-aloop` 模块 + 机器上有能切的 ALSA 控制项，
  大部分手机都不满足。失败时 `MicInjector` 如实回报，`AnswerReader` 回退到扬声器朗读 ——
  也就是用户能听见、但「一起作业」那边听不到。这条**没有在真机上验证过**。
- **没有 root 也灌不进去**，只能扬声器。
- **悬浮窗里的相机**在 `TYPE_APPLICATION_OVERLAY` 窗口上不一定能起，起不来会提示改用全屏相机。
- **后台拉起 `CameraActivity`** 在 Android 10+（API 29+）会被系统拦；minSdk 28，
  在 Android 9 的机器上没问题。
- 本机（Termux aarch64）**没有 Android SDK、没有 root、没有 adb**，装不了 APK，
  所以**没有任何功能在真机上跑过**。验证只做到：CI 编译通过 + 下载 APK 静态检查
  （清单声明、权限、FileProvider、dex 里能找到作业模块的类和字符串常量、签名块存在）。

## Git

- 分支 `main`。`.gitignore` 已排除 `build/`、`.gradle/`、`*.apk`、`local.properties`。
- 未经主人确认不要代跑 `git add` / `commit` / `push` / `reset`。
- 签名密钥 `key.jks` 已入库（密码 kernelsu / ksu，别名 ksu），保证每次构建签名一致、可覆盖升级。
  密钥文件和 `gradle.properties` 里的密码都是明文提交的，**这是有意为之**（CI 要能无人工签名），
  但意味着这把钥匙已经算泄露，正式分发前必须换。
