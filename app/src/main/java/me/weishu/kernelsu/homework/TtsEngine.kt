package me.weishu.kernelsu.homework

import android.content.ComponentName
import android.content.Context
import android.os.LocaleList
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 本地语音朗读。
 *
 * 用系统内置的离线 TTS 引擎（不联网、不依赖云端语音），支持两种出口：
 * - [speak]：直接从扬声器播放，用户能听见；
 * - [synthesizeToFile]：先把语音合成成一个 WAV 文件，交给 [MicInjector] 尝试灌进目标 App 的麦克风。
 */
class TtsEngine(
    private val app: Context,
) : TextToSpeech.OnInitListener {

    interface Listener {
        fun onStart() {}
        fun onDone() {}
        fun onError(message: String) {}
    }

    @Volatile
    var isReady: Boolean = false
        private set

    /** 系统里有没有可用的离线 TTS 引擎。 */
    val hasLocalEngine: Boolean
        get() = TextToSpeech.Engine.getEngines(app).any {
            val pkg = it.packageName
            pkg.startsWith("com.") && !pkg.startsWith("android.") &&
                    !pkg.contains("cloud") && !pkg.contains("vendor")
        }

    val engines: List<ComponentName>
        get() = TextToSpeech.Engine.getEngines(app)

    private var tts: TextToSpeech? = null
    private var onSpeakDone: (() -> Unit)? = null
    private var onSpeakError: ((String) -> Unit)? = null

    @Synchronized
    fun init() {
        if (tts != null) return
        val t = TextToSpeech(app.applicationContext, this)
        tts = t
    }

    /** 等 TTS 引擎就绪（最多 [timeoutMs]），已就绪时立即返回。 */
    @Synchronized
    fun awaitReady(timeoutMs: Long = 3000L): Boolean {
        if (isReady) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isReady && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return isReady
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            return
        }
        val ok = t.setLanguage(Locale.forLanguageTag("zh-CN"))
        if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault())
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) onSpeakDone?.invoke()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "utterance error code=$errorCode")
                if (utteranceId == UTTERANCE_ID) onSpeakError?.invoke("语音引擎出错")
            }
        })
        isReady = true
    }

    /** 直接从扬声器朗读。 */
    fun speak(text: String, listener: Listener? = null) {
        if (text.isBlank()) return
        val t = tts
        if (t == null || !isReady) {
            listener?.onError("语音引擎还没就绪")
            return
        }
        listener?.onStart()
        onSpeakDone = { listener?.onDone() }
        onSpeakError = { msg -> listener?.onError(msg) }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /**
     * 把语音合成到 [file]，供麦克风注入使用。
     * 合成失败时回调 false，调用方应回退到扬声器播放。
     */
    fun synthesizeToFile(text: String, file: File, onDone: (Boolean) -> Unit) {
        if (text.isBlank()) {
            onDone(false)
            return
        }
        val t = tts
        if (t == null || !isReady) {
            onDone(false)
            return
        }
        if (file.parentFile != null && !file.parentFile.exists()) {
            file.parentFile.mkdirs()
        }
        val id = "hw_${System.currentTimeMillis()}"
        val code = t.synthesizeToFile(text, file, object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) onDone(true)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "synthesize error code=$errorCode")
                onDone(false)
            }
        })
        if (code != TextToSpeech.SUCCESS) {
            onDone(false)
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    @Synchronized
    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        isReady = false
    }

    fun localeTag(): String = LocaleList.getDefault().get(0)?.language ?: "zh-CN"

    private companion object {
        const val TAG = "HomeworkTts"
        const val UTTERANCE_ID = "homework_tts"
    }
}
