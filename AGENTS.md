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

## 版本契约（别随便改）

- AGP 9.4.1 / Kotlin 2.4.20 / Compose 1.12.0，见 `gradle/libs.versions.toml`。
- **AGP 9 起内置 Kotlin 支持，不要再加 `org.jetbrains.kotlin.android` 插件**，只保留
  `com.android.*` + `org.jetbrains.kotlin.plugin.compose` + serialization + protobuf + apksign。
- minSdk = 28（Android 9+）。CameraX 用 1.4.x。

## 代码结构

- 底座 UI 壳（`ui/`、`ui/theme/`、`ui/component/`、`ui/navigation3/`）：KSU 管理器原样保留，
  双主题（Miuix / Material）、液态玻璃底栏、路由都在。
- 新增作业功能：
  - `homework/Prompt.kt` — 拍题 / 改作文 / 生作文提示词（按年级 → 学段分支）
  - `homework/ImageUtil.kt` — 图片解码 / 压缩 / 缩略图（长边 2048、质量自适应）
  - `homework/AiClient.kt` — OpenAI 兼容 chat/completions + models 客户端（OkHttp）
  - `homework/HomeworkStore.kt` — 设置 + 答题统计 + 历史（SharedPreferences / JSON）
  - `homework/TtsEngine.kt` — 本地语音朗读（TextToSpeech）
  - `homework/MicInjector.kt` — 朗读音频注入「一起作业」麦克风（root ALSA loopback，回退扬声器）
  - `homework/UpdateChecker.kt` — 应用内更新：GitHub Releases + 镜像源下载 + 安装
  - `homework/FloatingWindowService.kt` — 悬浮窗服务：拍照 / 答案显示 / 朗读按钮 / 统计
  - `homework/FloatingHomeworkView.kt` — 悬浮窗 View（浮动按钮 + 面板 + 相机预览）
  - `homework/CameraActivity.kt` — 自建相机（CameraX，带闪光灯），产出 content Uri
  - `homework/HomeworkScreen.kt` — 主界面入口（Home 页作业卡片 → 详情页）
- 入口：`ui/screen/home/` 增加作业卡片；`Routes.kt` 增加 `Route.Homework`。

## 自派任务（全部完成才算交付）

1. 悬浮窗服务 + 相机拍照 + AI 答案显示在悬浮窗
2. 本地 TTS 朗读 + 朗读按钮（AI 给答案后出现，点击朗读并尝试注入「一起作业」麦克风）
3. 答题正确统计（App 内计数，保存本地）
4. 应用内更新（GitHub 仓库 + 镜像源下载）
5. 推送到 GitHub 触发 CI 远程编译，下载产物验证
6. 体验官走查：以用户视角体验，找出影响体验的问题，给出建议并更新

## Git

- 分支 `main`。`.gitignore` 已排除 `build/`、`.gradle/`、`*.apk`、`local.properties`。
- 未经主人确认不要代跑 `git add` / `commit` / `push` / `reset`。
- 签名密钥 `key.jks` 已入库（密码 kernelsu / ksu），保证每次构建签名一致、可覆盖升级。
