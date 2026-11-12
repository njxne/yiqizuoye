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
- **版本号和「应用内更新」是绑在一起的，别只改一边**。`build.gradle.kts` 的 `getVersionCode()` = `30000 + 提交数`，
  CI 发版的 tag 用同一个公式（`30000 + git rev-list --count HEAD`）。这样 App 自身的 versionCode 恰好等于
  它对应那个 release 的 tag 数字，`UpdateChecker.versionCodeOf(tag) > currentVersionCode` 才会**只在真的push了新代码时**为真。
  `UpdateChecker` 是从 tag 里抠数字比大小的，所以 tag 必须形如 `v30014` —— 别加 `-beta` 之类的后缀，
  也别另起一套数字，否则应用内更新要么永远不提示、要么永远提示有新版。

## 代码结构

- 底座 UI 壳（`ui/theme/`、`ui/component/`、`ui/navigation3/`）：**只保留 KSU 的引擎与控件库**
  （miuix / liquidglass / material 组件、双主题、液态玻璃底栏），**KSU 管理器自己的功能界面已全部删除**。
- 底栏只剩两个分栏：**作业 / 设置**；打开 App 直接是作业页。详见下文「结构精简（第三轮）」。
- 新增作业功能：
  - `homework/Prompt.kt` — 拍题 / 批改作文 / 生成作文提示词（按年级 → 学段分支，自动定字数）
  - `homework/HomeworkSolver.kt` — 拍题 → AI 的链路
  - `homework/AnswerSplitter.kt` — 把答案按 `#第N题` 切成单题（纯 Kotlin，无 Android 依赖，便于脱离设备跑测试）
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
6. 体验官走查：以用户视角体验，找出影响体验的问题，给出建议并更新 ✅（跑了三轮，见下）
7. 能脱离设备验证的部分做真实单测 ✅（`Prompt` / `AnswerSplitter` 是纯 Kotlin，用 kotlinc 编译成 jar 真跑，16 项断言全过；
   这两个类故意不引 Android 依赖就是为了这一步）

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

## 体验官走查记录（第二轮，已修）

第一轮修完又从「用户第一次装这个 App 会发生什么」的角度走了一遍，找出这些**不修就会被骂**的问题：

1. **应用内更新在国内手机上永远「检查失败」**。`UpdateChecker.check()` 只直连 `api.github.com`，
   镜像源列表只作用于下载。国内直连不通就直接报错，用户以为 App 坏了。
   改成先直连、不通再走 `gh-proxy.com` 代理 API。**实测**：ghfast.top / ghproxy.net 对
   `https://api.github.com/...` 路径直接返回 403 `Invalid input.`，只有 gh-proxy.com 能代理（200 + 完整 JSON），
   所以 API 代理源只有一个，别照抄下载用的那三个。
2. **点「安装」被静默拒绝**。Android 8+ 没开「安装未知来源」时调起安装页直接失败，用户只看到没反应。
   加了 `UpdateChecker.canInstall()`（`PackageManager.canRequestPackageInstalls()`），没开就跳
   `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` 并在界面上说明原因。
3. **麦克风注入写错声卡**。原来列清单用 `tinymix -c $card` 失败后回退到 `tinymix`（默认主板卡 0），
   但写开关时又用 `-c $card` 指到 aloop 虚拟卡上——那张卡根本没有麦克风源控制项，**等于永远写不进去**。
   改成先从 `/proc/asound/cards` 里找第一张非 loopback 的主板卡，列清单和写开关都用同一张。
4. **朗读按钮可能永久卡住**。目标 App 没在录音时，往 `/dev/snd/hw0,0` 写会一直堵在缓冲区上不返回，
   调用线程挂死，按钮像坏了一样。改成按音频时长算超时（`时长 + 15s`），超时就回退扬声器，
   不再让用户干等。
5. **「声音已灌进麦克风」这句话误导**。音频其实已经写完了，再说「去 App 里点开始朗读」，
   用户照做也听不到。改成明说：要求「一起作业」**正在录音**才收得到，这次没收到就先在那边点开始朗读再回来点一次。
6. **发版一次 500 就整个 run 标红**。实测 `gh release create` 会偶发 HTTP 500（v30011 那次就是），
   已经构建好、上传好的 APK 全部作废。拆成「建 release」和「传资产」两步，各自重试
   （建 3 次、传 5 次）。
7. **我自己改 workflow 时踩的坑**：给 `--notes` 传多行字符串，续行写成顶格，直接截断了 YAML 的
   `run: |` 块 → workflow 语法错误 → run 秒失败且**一个 job 都没有**（run 的 `name` 会变成文件路径而不是 `Build`）。
   已改成单行 `printf`。**教训：改完 workflow 先本地 `python3 -c "import yaml;yaml.safe_load(open(...))"` 校验。**
8. **相机拍照在 Android 9 上会直接失败**。原来把照片写进 MediaStore（`Images.Media.EXTERNAL_CONTENT_URI`），
   而 manifest 里没有 `WRITE_EXTERNAL_STORAGE` —— API 28 上写 MediaStore 要这个权限，会抛
   `SecurityException`，用户点「拍照」只看到一句「拍照失败」。
   而且写进 MediaStore 的照片**永久留在用户相册里**，越拍越多，App 自己也清不掉。
   改成写 `cacheDir/hw/` 再经已有的 FileProvider 交出：不用申权限、不污染相册、空间紧张时系统会回收，
   content Uri 一样能跨进程传给 Service。
9. **没答案也能点「答对了」，统计被凭空灌水**。悬浮窗里没拍过题就点「答对了」，照样 `+1` 并写一条空记录；
   主页那边 `onError` 会把 `answer` 设成「出错了：…」，按钮照样可点，同样计入「答对」。
   悬浮窗加了「答案为空就提示先拍照、不计数」的闸；主页加了 `hasAnswer` 标志（只有 `onAnswer` 才置真），
   「朗读 / 答对了 / 答错了」在没解出答案时置灰，并显示一句说明。

另外实测确认：`AiClient.DEFAULT_BASE`（`https://ai.murk.top/v1/chat/completions`）这个默认接口地址是**可达**的，
无 Key 时返回 `401 Invalid token` —— 也就是说默认值能用，用户只需要填自己的 API Key。

## 结构精简（第三轮，已做）

用户原话：引擎控件要用 KSU 开源仓库同款，**不要自己模仿写一套**；KSU 管理器自己那堆功能不该留在这个作业 App 里；
「一堆没用的没删，堆在那里」「超级用户分栏怎么还在」。

本轮把 KSU 管理器自带的功能界面**整块删掉**，只留它的引擎和控件库：

- **底栏（含 Material 版）**：原来 4 个分栏（首页 / 超级用户 / 模块 / 设置），现在 **Miuix 和 Material 两套底栏、
  两套侧边导航栏**都只剩 `作业 / 设置`。
  坑：**material-icons-extended 的图标必须逐个显式 `import`**（光写 `Icons.Rounded.X` 不 import 就解析不到，
  `School` / `Description` 都踩过）；`BottomBarMaterial.kt` / `NavigationRailMaterial.kt` 是 Miuix 之外的第二套，
  只改 Miuix 会漏。
- **删掉的界面包**（`ui/screen/` 下）：`home` `superuser` `module` `modulerepo` `install`
  `executemoduleaction` `sulog` `scriptrun` `appprofile` `template` `templateeditor` `colorpalette`，
  以及 `flash/` 整个包。
- **删掉的连带代码**：`ui/webui/` 整个包（WebView 模块管理界面，只被主题用到的 `MonetColorsProvider`
  一并去掉）、`IntentDispatcher.kt`、`UidGroupUtils.kt`、`module/Shortcut.kt`、`util/Serialization.kt`、
  `util/Downloader.kt`、`component/profile/` 整个包、8 个 ViewModel、4 个 Repository、
  `AndroidManifest.xml` 里的 `WebUIActivity`。
- **删掉的 KSU 管理器组件**（`ui/component/` 下，删前逐个 grep 确认全项目零引用）：`rebootlistpopup/`
  `choosekmidialog/` `statustag/`，以及随之变死的 `MenuPositionProvider` `KsuValidCheck` `AppIconImage`
  `KeyEventBlocker` `ObserveAsEvents` `ScrollToTop` `dialog/DownloadDialog`，还有卸载弹窗
  `uninstalldialog/` 三个文件、发日志的 `miuix/SendLogDialog.kt` 和 `material/SendLogBottomSheet.kt`。
- **设置页**：删掉 SU 兼容、内核卸载、SELinux 隐藏、SU 日志、ADB root、Shell SU、软重启、模块默认卸载、
  Web 调试、自动越狱、模块更新检查、卸载、发送日志 —— 现在只剩「检查更新 / 界面风格 / 关于」。
- **保留**：KSU 引擎（`Natives` / `data/` / `KernelSUApplication`）、控件库（`ui/component/` 里剩下的
  miuix / liquidglass / material / markdown / dialog、`ui/theme/`）、关于页、`homework/` 全部。
- **改名**：应用名从 `KernelSU` 改成 `一起作业`（`KSU_NAME` 默认值），release 资产名从
  `KernelSU_Android9-release.apk` 改成 `yiqizuoye-release.apk`（`archivesName`）。
  **包名没动**（还是 `me.weishu.kernelsu`）——改包名会断掉覆盖安装和 FileProvider authority，得不偿失。
  `UpdateChecker` 是动态读 release 资产名的，所以改名不影响应用内更新。

## 体验官走查记录（第三轮，已修）

KSU 管理器那堆东西清完之后，又从「用户第一次装这个 App 会发生什么」走了一遍：

1. **Material 版底栏还在显示 KSU 的分栏**（最打脸的一处）。上一轮只改了 Miuix 版底栏，
   `BottomBarMaterial.kt` / `NavigationRailMaterial.kt` 里还是 `home / superuser / module / settings` 四个，
   而 `MainPagerConfig.PAGE_COUNT` 已经是 2 —— 用户一切 Material 主题就看到「超级用户」分栏，点第 3、4 个还会越界。
   两套底栏 + 两套侧边导航栏现在都只剩 `作业 / 设置`。
2. **设置页塞满了 KSU 的 root / 内核开关**。SU 兼容、内核卸载、SELinux 隐藏、SU 日志、ADB root、
   Shell SU、软重启、模块默认卸载、Web 调试、自动越狱、模块更新检查、卸载、发送内核日志 ——
   一个作业 App 里全是看不懂的东西。现在只剩「检查更新 / 界面风格 / 关于」。
   连带删掉的：卸载弹窗、发日志弹窗，以及它们变死代码后全项目零引用的一批组件
   （`rebootlistpopup` `choosekmidialog` `statustag` `MenuPositionProvider` `KsuValidCheck` `AppIconImage`
   `KeyEventBlocker` `ObserveAsEvents` `ScrollToTop` `dialog/DownloadDialog`）。
3. **应用名和 APK 名还是「KernelSU」**。装到桌面叫 KernelSU、下载下来叫 `KernelSU_Android9-release.apk`，
   跟「一起作业」是两个东西。应用名改成 `一起作业`，release 资产名改成 `yiqizuoye-release.apk`。
   包名没动（改了会断覆盖安装和 FileProvider authority）。
4. **「API Key（可留空）」是误导**。默认接口 `ai.murk.top` 没 Key 直接 401，留空用不了。改成「多数接口必填」。
5. **首页打开是一大长串设置，没有上手引导**。加了一张「三步开始」卡片：填 AI 设置 → 开悬浮窗 → 回桌面点「拍」。

## 体验官走查记录（第四轮，已做）

走查时发现一个比「控件风格」更实在的问题：作业页用的是 Material3 控件，而 App 默认界面风格是 **Miuix**。
`KernelSUTheme` 只在 `UiMode.Material` 下才包 `MaterialExpressiveTheme`，也就是说默认情况下作业页的
卡片、按钮、输入框、顶栏全都读不到 App 的配色与字体，会掉进 Material3 的默认深色配色 —— 跟设置页、
关于页完全是两个样子。

修法：在 `HomeworkScreen.kt` 里给作业页显式套上 KSU 自己的 `MaterialKernelSUTheme`
（`app/src/main/java/me/weishu/kernelsu/homework/HomeworkScreen.kt:149`），用 App 真实的配色与字体，
两种界面风格下都不会走样。这一轮同时删掉了 5 个全项目零引用的死文件
（`HomeworkEntryCard` `DropdownItem` `ExpressiveToggleButton` `PinyinUtil` `keyColorOptions`）。

**还留着的一块**：作业页内层仍是 Material3 控件（现在配色正确了），没有逐个换成 miuix 控件。
miuix 0.9.4 的 `TextField` 已经改用 `TextFieldState`（不再是 `value`/`onValueChange`），要把 5 个
输入框连状态管理一起重写；本机没有编译器、只能靠 CI 盲改，风险很高，所以这轮先做确定正确的配色修复，
控件逐个换掉留作后续。

- 悬浮窗是原生 `View` 搭的（KSU 没有悬浮窗，没有可抄的控件），这个不算违背「同款」。

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
