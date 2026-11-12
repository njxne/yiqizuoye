package me.weishu.kernelsu.homework

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 悬浮窗服务。
 *
 * 负责：拉起悬浮窗、实时相机预览、拍照后调 AI、把答案显示在悬浮窗、朗读（含尝试灌进
 * 目标 App 麦克风）、答对题数统计。
 */
class FloatingWindowService : Service() {

    companion object {
        const val ACTION_START = "me.weishu.kernelsu.action.HOMEWORK_FLOAT_START"
        const val ACTION_STOP = "me.weishu.kernelsu.action.HOMEWORK_FLOAT_STOP"
        const val EXTRA_COLLAPSE = "collapse"

        private const val NOTIFICATION_ID = 88001
        private const val CHANNEL_ID = "homework_float"
        private const val TAG = "HomeworkFloat"

        fun start(context: Context, collapse: Boolean = false) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_COLLAPSE, collapse)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }

        fun hasOverlayPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
            else true
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingImages = ArrayList<android.net.Uri>()
    private var solving = false
    private var reading = false
    private var viewAdded = false
    private var cameraStarted = false

    private var windowView: FloatingHomeworkView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var camera: HomeworkCamera.Controller? = null
    private lateinit var tts: TtsEngine

    private val callback = object : FloatingHomeworkView.Callback {
        override fun onCapture() = capture()
        override fun onSolve() = solve()
        override fun onClear() = clearAll()
        override fun onRead() = readAnswer()
        override fun onCopy() = copyAnswer()
        override fun onMarkCorrect() = markAnswer(true)
        override fun onMarkWrong() = markAnswer(false)
        override fun onClose() {
            HomeworkStore.setBool(this@FloatingWindowService, HomeworkStore.K_FLOAT, false)
            stopSelf()
        }

        override fun onExpand() {
            val v = windowView ?: return
            v.setExpanded(true)
            ensureCamera()
        }

        override fun onOpenApp() = openTargetApp()
        override fun onMove(dx: Int, dy: Int) {
            val p = windowParams ?: return
            p.x = (p.x + dx).coerceAtLeast(0)
            p.y = (p.y + dy).coerceAtLeast(0)
            runCatching {
                getSystemService(WindowManager::class.java).updateViewLayout(windowView, p)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TtsEngine(applicationContext).also { it.init() }
        refreshStats()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                if (!hasOverlayPermission(this)) {
                    Toast.makeText(this, "没有「显示在其他应用上层」权限，开不了悬浮窗", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                val collapse = intent?.getBooleanExtra(EXTRA_COLLAPSE, false) ?: false
                startForegroundCompat()
                addWindowView(collapse)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching {
            windowView?.let { getSystemService(WindowManager::class.java).removeView(it) }
        }
        windowView = null
        camera?.stop()
        camera = null
        tts.stop()
        tts.shutdown()
        windowParams = null
        viewAdded = false
        super.onDestroy()
    }

    // ==================== 悬浮窗 ====================

    private fun startForegroundCompat() {
        val notification = buildNotification()
        val type = if (cameraPermissionGranted()) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "作业悬浮窗", NotificationManager.IMPORTANCE_LOW).apply {
                description = "悬浮窗开着的时候在后台留一条通知"
                setShowBadge(false)
            }
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("一起作业悬浮窗")
            .setContentText("点开就能拍题，AI 直接给答案")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun addWindowView(collapse: Boolean) {
        if (viewAdded) return
        val wm = getSystemService(WindowManager::class.java)
        val view = FloatingHomeworkView(this, callback)
        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 400
        }
        runCatching { wm.addView(view, windowParams) }.onFailure {
            Log.w(TAG, "addView failed", it)
            Toast.makeText(this, "悬浮窗加不上去，请检查权限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        windowView = view
        viewAdded = true
        view.setExpanded(!collapse)
        if (!collapse) ensureCamera()
    }

    // ==================== 相机 ====================

    private fun ensureCamera() {
        val v = windowView ?: return
        if (!cameraPermissionGranted()) {
            v.setHint("没有相机权限，先点「拍照」会打开全屏相机")
            return
        }
        val c = camera ?: HomeworkCamera.Controller(applicationContext, cameraOwner, v.previewView)
            .also { camera = it }
        if (!cameraStarted) {
            cameraStarted = true
            Thread {
                if (!c.start()) {
                    cameraStarted = false
                    mainHandler.post { v.setHint("悬浮窗里开不了相机，改用全屏相机") }
                }
            }.start()
        }
    }

    private fun capture() {
        val v = windowView ?: return
        val c = camera
        if (c == null || !c.isReady) {
            openCameraActivity()
            return
        }
        c.takePicture(
            onUri = { uri ->
                mainHandler.post {
                    if (uri != null) {
                        pendingImages.add(uri)
                        v.setHint("已拍 ${pendingImages.size} 张，点「解题」")
                    }
                }
            },
            onError = { msg -> mainHandler.post { v.setHint(msg) } },
        )
    }

    // ==================== 解题 ====================

    private fun solve() {
        val v = windowView ?: return
        if (pendingImages.isEmpty()) {
            v.setHint("先拍一张题再点「解题」")
            return
        }
        if (solving) return
        if (HomeworkStore.get(this, HomeworkStore.K_BASE).isBlank()) {
            v.setError("还没填 AI 接口地址")
            return
        }
        solving = true
        v.setLoading(true)
        val ctx = this
        HomeworkSolver.solve(
            ctx,
            ArrayList(pendingImages),
            onAnswer = { answer ->
                mainHandler.post {
                    v.setAnswer(answer)
                    v.setHint("答案出来了，可以点「朗读」")
                    solving = false
                    v.setLoading(false)
                }
            },
            onError = { msg ->
                Log.w(TAG, "solve failed: $msg")
                mainHandler.post {
                    v.setError(msg)
                    solving = false
                    v.setLoading(false)
                }
            },
        )
    }

    private fun clearAll() {
        pendingImages.clear()
        val v = windowView ?: return
        v.setAnswer("")
        v.setHint("点「拍照」开始，可连拍多页")
    }

    // ==================== 朗读 ====================

    private fun readAnswer() {
        val v = windowView ?: return
        val text = v.currentAnswer().ifBlank {
            v.setHint("还没有答案，先拍题")
            return
        }
        if (reading) {
            tts.stop()
            reading = false
            v.setHint("已停止朗读")
            return
        }
        reading = true
        v.setHint("正在准备朗读…")
        Thread {
            val file = File(filesDir, "tts_${System.currentTimeMillis()}.wav")
            tts.awaitReady(5000)
            tts.synthesizeToFile(text, file) { ok ->
                if (!ok) {
                    reading = false
                    speakOut(text) { v.setHint("朗读中（扬声器）") }
                    return@synthesizeToFile
                }
                MicInjector.targetPackage =
                    HomeworkStore.get(this@FloatingWindowService, "target_package", MicInjector.targetPackage)
                MicInjector.injectAsync(this@FloatingWindowService, file) { res ->
                    mainHandler.post {
                        reading = false
                        v.setHint(res.message)
                        if (!res.ok) {
                            // 灌不进麦克风就老老实实从扬声器读出来。
                            speakOut(text) { v.setHint("朗读中（扬声器）") }
                        }
                    }
                }
            }
        }.start()
    }

    private fun speakOut(text: String, onStart: () -> Unit) {
        onStart()
        tts.speak(text)
    }

    // ==================== 统计 ====================

    private fun markAnswer(correct: Boolean) {
        HomeworkStore.markAnswer(this, correct)
        val v = windowView ?: return
        val s = HomeworkStore.stats(this)
        v.setStats(s.total, s.correct, s.streak)
        val ctx = this
        HomeworkStore.addRecord(ctx, HomeworkStore.Record(
            time = System.currentTimeMillis(),
            subject = HomeworkStore.get(ctx, HomeworkStore.K_SUBJECT, HomeworkStore.DEF_SUBJECT),
            grade = HomeworkStore.get(ctx, HomeworkStore.K_GRADE, HomeworkStore.DEF_GRADE),
            question = v.currentAnswer().take(300),
            answer = "",
            correct = correct,
            image = "",
        ))
        v.setHint(if (correct) "已记一笔答对" else "已记一笔答错")
    }

    private fun refreshStats() {
        val s = HomeworkStore.stats(this)
        windowView?.setStats(s.total, s.correct, s.streak)
    }

    // ==================== 其它 ====================

    private fun copyAnswer() {
        val v = windowView ?: return
        val text = v.currentAnswer()
        if (text.isBlank()) {
            v.setHint("还没有答案")
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("作业答案", text))
        v.setHint("答案已复制")
    }

    private fun openTargetApp() {
        val pkg = MicInjector.targetPackage
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            windowView?.setHint("没找到 $pkg，请在设置里改成正确的包名")
        }
    }

    private fun openCameraActivity() {
        val v = windowView ?: return
        v.setHint("打开全屏相机拍照…")
        val receiver = object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) {
                val uris = data?.getParcelableArrayList<android.net.Uri>(CameraActivity.EXTRA_URIS)
                    ?: return
                uris.forEach { pendingImages.add(it) }
                mainHandler.post {
                    v.setHint("已拍 ${pendingImages.size} 张，点「解题」")
                }
            }
        }
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra(CameraActivity.EXTRA_RESULT_RECEIVER, receiver)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun cameraPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
