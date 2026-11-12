package me.weishu.kernelsu.homework

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.KernelSUApplication
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import java.io.File

/** 作业页：设置、统计、悬浮窗开关、拍题、朗读、检查更新、历史。 */
@Composable
fun HomeworkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    var baseUrl by remember { mutableStateOf(HomeworkStore.get(context, HomeworkStore.K_BASE, HomeworkStore.DEF_BASE)) }
    var apiKey by remember { mutableStateOf(HomeworkStore.get(context, HomeworkStore.K_KEY)) }
    var model by remember { mutableStateOf(HomeworkStore.get(context, HomeworkStore.K_MODEL, HomeworkStore.DEF_MODEL)) }
    var grade by remember { mutableStateOf(HomeworkStore.get(context, HomeworkStore.K_GRADE, HomeworkStore.DEF_GRADE)) }
    var subject by remember { mutableStateOf(HomeworkStore.get(context, HomeworkStore.K_SUBJECT, HomeworkStore.DEF_SUBJECT)) }
    var targetPackage by remember {
        mutableStateOf(HomeworkStore.get(context, "target_package", MicInjector.targetPackage))
    }

    var stats by remember { mutableStateOf(HomeworkStore.stats(context)) }
    var overlayGranted by remember { mutableStateOf(FloatingWindowService.hasOverlayPermission(context)) }
    var floatOn by remember { mutableStateOf(HomeworkStore.getBool(context, HomeworkStore.K_FLOAT)) }

    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelMenu by remember { mutableStateOf(false) }
    var gradeMenuOpen by remember { mutableStateOf(false) }
    var subjectMenuOpen by remember { mutableStateOf(false) }

    var history by remember { mutableStateOf(HomeworkStore.history(context)) }
    var note by remember { mutableStateOf<String?>(null) }

    var picked by remember { mutableStateOf(emptyList<Uri>()) }
    var answer by remember { mutableStateOf<String?>(null) }
    var solving by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }

    var updateText by remember { mutableStateOf<String?>(null) }
    var pendingRelease by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }

    val tts = remember { TtsEngine(context.applicationContext).also { it.init() } }

    // 本地语音引擎到底能不能用，得等初始化完才知道，直接告诉用户，别让人白点。
    var ttsReady by remember { mutableStateOf(false) }
    var ttsLocal by remember { mutableStateOf(false) }

    var showAppPicker by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    var essayTopic by remember { mutableStateOf(HomeworkStore.get(context, HomeworkStore.K_ESSAY_TOPIC)) }
    var essayResult by remember { mutableStateOf<String?>(null) }
    var essayBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        tts.awaitReady(5000)
        ttsReady = true
        ttsLocal = tts.hasLocalEngine
    }

    fun say(msg: String) {
        note = msg
    }

    /**
     * 朗读一段文字：先试着灌进目标 App 的麦克风，灌不进去就从扬声器念出来。
     *
     * 主页和悬浮窗以前各写一套，主页的朗读只会从扬声器念，用户点完发现「一起作业」那边
     * 没声音，就会以为功能坏了。现在统一走 [AnswerReader]。
     */
    fun readText(text: String, onDone: () -> Unit = { say("朗读结束") }) {
        if (AnswerReader.isBusy()) {
            AnswerReader.stop(tts)
            say("已停止朗读")
            return
        }
        val accepted = AnswerReader.read(context, tts, text, object : AnswerReader.Listener {
            override fun onStart() {
                say("朗读中…")
            }

            override fun onDone() {
                onDone()
            }

            override fun onError(message: String) {
                say(message)
            }
        })
        if (!accepted) say("这次没接上朗读")
    }

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("一起作业") },
                navigationIcon = { TopBarBackButton(onClick = onBack) },
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ---------- 统计 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("答题统计", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("答对 ${stats.correct} / ${stats.total}　连对 ${stats.streak}")
                    Text("正确率 ${(stats.rate * 100).toInt()}%")
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        HomeworkStore.resetStats(context)
                        stats = HomeworkStore.stats(context)
                        say("统计已清零")
                    }) { Text("清零统计") }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---------- 悬浮窗 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("悬浮窗", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (overlayGranted) "悬浮窗权限：已授予"
                        else "悬浮窗权限：未授予，要去系统设置里打开「显示在其他应用上层」",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            // 权限可能刚在设置里开过，点的时候现查一遍，别认准进来时那一次的结果。
                            val granted = FloatingWindowService.hasOverlayPermission(context)
                            overlayGranted = granted
                            if (granted) {
                                FloatingWindowService.start(context)
                                HomeworkStore.setBool(context, HomeworkStore.K_FLOAT, true)
                                floatOn = true
                                say("悬浮窗已开启，去桌面点那个「拍」按钮")
                            } else {
                                openOverlaySettings(context) { say("打不开权限页，请手动去系统设置里开") }
                            }
                        }) { Text("开启悬浮窗") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                FloatingWindowService.stop(context)
                                HomeworkStore.setBool(context, HomeworkStore.K_FLOAT, false)
                                floatOn = false
                                say("悬浮窗已关闭")
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            ),
                        ) { Text("关闭") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            openOverlaySettings(context) { say("打不开权限页，请手动去系统设置里开") }
                        }) { Text("去开权限") }
                        Spacer(Modifier.width(8.dp))
                        Text(if (floatOn) "开" else "关", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---------- 拍题 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("拍题", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("已拍 ${picked.size} 张")
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            launchCamera(context) { uris ->
                                picked = uris
                                answer = null
                            }
                        }) { Text("拍照") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = picked.isNotEmpty() && !solving,
                            onClick = {
                                solving = true
                                answer = null
                                say("正在让 AI 看题…")
                                HomeworkSolver.solve(
                                    context,
                                    picked,
                                    onAnswer = { ans ->
                                        answer = ans
                                        solving = false
                                        say("答案出来了")
                                    },
                                    onError = { msg ->
                                        answer = "出错了：$msg"
                                        solving = false
                                        say("出错了")
                                    },
                                )
                            },
                        ) { Text("解题") }
                    }
                    if (answer != null) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(answer!!, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                if (reading || AnswerReader.isBusy()) {
                                    AnswerReader.stop(tts)
                                    reading = false
                                    say("已停止朗读")
                                } else {
                                    reading = true
                                    readText(answer!!) {
                                        reading = false
                                        say("朗读结束")
                                    }
                                }
                            }) { Text("朗读") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                HomeworkStore.markAnswer(context, true)
                                stats = HomeworkStore.stats(context)
                                say("记一笔答对")
                            }) { Text("答对了") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    HomeworkStore.markAnswer(context, false)
                                    stats = HomeworkStore.stats(context)
                                    say("记一笔答错")
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                ),
                            ) { Text("答错了") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---------- AI 设置 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("AI 设置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            HomeworkStore.set(context, HomeworkStore.K_BASE, it)
                        },
                        label = { Text("接口地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            HomeworkStore.set(context, HomeworkStore.K_KEY, it)
                        },
                        label = { Text("API Key（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = model,
                                onValueChange = {
                                    model = it
                                    HomeworkStore.set(context, HomeworkStore.K_MODEL, it)
                                },
                                label = { Text("模型") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DropdownMenu(showModelMenu, onDismissRequest = { showModelMenu = false }) {
                                models.forEach {
                                    DropdownMenuItem(
                                        text = { Text(it) },
                                        onClick = {
                                            model = it
                                            HomeworkStore.set(context, HomeworkStore.K_MODEL, it)
                                            showModelMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            showModelMenu = false
                            say("正在取模型列表…")
                            Thread {
                                try {
                                    val list = AiClient.listModels(
                                        (context.applicationContext as KernelSUApplication).okhttpClient,
                                        baseUrl,
                                        apiKey,
                                    )
                                    models = list
                                    showModelMenu = true
                                    say("取到 ${list.size} 个模型，选一个")
                                } catch (e: Exception) {
                                    say("取模型失败：${e.message}")
                                }
                            }.start()
                        }) { Text("取模型") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Box(Modifier.weight(1f)) {
                            Surface(
                                onClick = { gradeMenuOpen = true },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(grade, modifier = Modifier.padding(12.dp))
                            }
                            DropdownMenu(gradeMenuOpen, onDismissRequest = { gradeMenuOpen = false }) {
                                Prompt.GRADES.forEach { g ->
                                    DropdownMenuItem(
                                        text = { Text(g) },
                                        onClick = {
                                            grade = g
                                            HomeworkStore.set(context, HomeworkStore.K_GRADE, g)
                                            gradeMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            Surface(
                                onClick = { subjectMenuOpen = true },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(subject, modifier = Modifier.padding(12.dp))
                            }
                            DropdownMenu(subjectMenuOpen, onDismissRequest = { subjectMenuOpen = false }) {
                                Prompt.SUBJECTS.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s) },
                                        onClick = {
                                            subject = s
                                            HomeworkStore.set(context, HomeworkStore.K_SUBJECT, s)
                                            subjectMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---------- 朗读 / 麦克风 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("朗读", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "朗读用系统本地语音，不联网。点「朗读测试」先听一下，再试着把声音灌进下面这个 App 的麦克风。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (!ttsReady) "本地语音引擎：正在准备…"
                        else if (ttsLocal) "本地语音引擎：可用（离线，不用联网）"
                        else "本地语音引擎：没找到中文语音数据，朗读多半念不出来，去系统设置里装一个离线语音引擎再试",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = targetPackage,
                        onValueChange = {
                            targetPackage = it
                            HomeworkStore.set(context, "target_package", it)
                            MicInjector.targetPackage = it
                        },
                        label = { Text("目标 App 包名（声音灌给谁）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                val pm = context.packageManager
                                installedApps = pm.getInstalledApplications(0)
                                    .map { it.packageName to (it.loadLabel(pm) as String) }
                                    .sortedBy { it.second }
                                showAppPicker = true
                            },
                        ) { Text("从已装应用里挑") }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (MicInjector.hasRoot()) "root：有"
                            else "root：没有（声音只能从扬声器出来）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            readText("朗读测试：今天天气很好")
                        }) { Text("朗读测试") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (!MicInjector.hasRoot()) {
                                    say("没有 root，声音只能从扬声器出来")
                                    return@Button
                                }
                                if (AnswerReader.isBusy()) {
                                    say("正在朗读别的内容，先等它读完")
                                    return@Button
                                }
                                readText("麦克风测试：这句话是给目标 App 的麦克风听的") {
                                    say("麦克风测试开始了，去目标 App 里点「开始朗读」")
                                }
                            },
                        ) { Text("测试麦克风注入") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---------- 作文 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("作文", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "拍下作文本，可以「批改」也可以「生成」。生成时把题干写在下面的框里，"
                            + "提示词会按年级自动定字数。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = essayTopic,
                        onValueChange = {
                            essayTopic = it
                            HomeworkStore.set(context, HomeworkStore.K_ESSAY_TOPIC, it)
                        },
                        label = { Text("作文题干（生成时用）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = picked.isNotEmpty() && !essayBusy,
                            onClick = {
                                essayResult = null
                                essayBusy = true
                                say("正在批改作文…")
                                HomeworkSolver.solve(
                                    context,
                                    picked,
                                    system = Prompt.gradeEssay(grade),
                                    onAnswer = { essayResult = it; essayBusy = false; say("批改结果出来了") },
                                    onError = { msg -> essayResult = "出错了：$msg"; essayBusy = false; say("出错了") },
                                )
                            },
                        ) { Text("批改作文") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = picked.isNotEmpty() && !essayBusy,
                            onClick = {
                                essayResult = null
                                essayBusy = true
                                say("正在写作文…")
                                HomeworkSolver.solve(
                                    context,
                                    picked,
                                    system = Prompt.writeEssay(grade, essayTopic),
                                    userText = essayTopic,
                                    onAnswer = { essayResult = it; essayBusy = false; say("作文写好了") },
                                    onError = { msg -> essayResult = "出错了：$msg"; essayBusy = false; say("出错了") },
                                )
                            },
                        ) { Text("生成作文") }
                    }
                    val essay = essayResult
                    if (essay != null) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(essay, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---------- 更新 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("应用内更新", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                    Text("更新源：${UpdateChecker.REPO}（下载走镜像源）", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            updateText = "正在检查…"
                            downloaded = null
                            pendingRelease = null
                            UpdateChecker.check(
                                currentVersionName = BuildConfig.VERSION_NAME,
                                currentVersionCode = BuildConfig.VERSION_CODE,
                                onResult = { r ->
                                    when (r) {
                                        is UpdateChecker.Result.Available -> {
                                            pendingRelease = r.release
                                            updateText = "发现 ${r.release.versionName}"
                                        }

                                        UpdateChecker.Result.UpToDate -> updateText = "已经是最新版"

                                        is UpdateChecker.Result.Error -> updateText = "检查失败：${r.message}"
                                    }
                                },
                            )
                        }) { Text("检查更新") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = pendingRelease != null,
                            onClick = {
                                val r = pendingRelease
                                if (r != null) {
                                    updateText = "开始下载…"
                                    downloaded = null
                                    UpdateChecker.download(
                                        context,
                                        r,
                                        onProgress = { read, total ->
                                            updateText = "下载中 $read / ${if (total > 0) total else 0}"
                                        },
                                        onDone = { file, err ->
                                            if (file != null) {
                                                downloaded = file
                                                updateText = "下载完成，点「安装」"
                                            } else {
                                                updateText = "下载失败：${err ?: "未知"}"
                                            }
                                        },
                                    )
                                }
                            },
                        ) { Text("下载") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = downloaded != null,
                            onClick = {
                                val f = downloaded
                                if (f != null) UpdateChecker.install(context, f)
                            },
                        ) { Text("安装") }
                    }
                    val text = updateText
                    if (text != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---------- 历史 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("历史", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.height(200.dp)) {
                        items(history, key = { it.time }) { record ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text("${record.subject}　${record.grade}　${
                                    if (record.correct) "答对" else "答错"
                                }")
                                Text(
                                    record.question.ifBlank { "（无内容）" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        HomeworkStore.clearHistory(context)
                        history = HomeworkStore.history(context)
                        say("历史已清空")
                    }) { Text("清空历史") }
                }
            }

            if (showAppPicker) {
                AlertDialog(
                    onDismissRequest = { showAppPicker = false },
                    title = { Text("挑一个 App 作为朗读的麦克风目标") },
                    text = {
                        LazyColumn {
                            items(installedApps, key = { it.first }) { (pkg, label) ->
                                TextButton(onClick = {
                                    targetPackage = pkg
                                    MicInjector.targetPackage = pkg
                                    HomeworkStore.set(context, "target_package", pkg)
                                    showAppPicker = false
                                    say("已选：$label")
                                }) {
                                    Column {
                                        Text(label)
                                        Text(pkg, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAppPicker = false }) { Text("关闭") }
                    },
                )
            }

            if (note != null) {
                Spacer(Modifier.height(12.dp))
                Text(note!!, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun openOverlaySettings(context: android.content.Context, onError: () -> Unit) {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSIONS).apply {
        data = android.net.Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure { onError() }
}

private fun launchCamera(context: android.content.Context, onResult: (List<Uri>) -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    val receiver = object : ResultReceiver(handler) {
        override fun onReceiveResult(resultCode: Int, data: Bundle?) {
            val uris = data?.getParcelableArrayList<Uri>(CameraActivity.EXTRA_URIS) ?: return
            onResult(uris)
        }
    }
    val intent = Intent(context, CameraActivity::class.java).apply {
        putExtra(CameraActivity.EXTRA_RESULT_RECEIVER, receiver)
    }
    context.startActivity(intent)
}
