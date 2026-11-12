package me.weishu.kernelsu.homework

import android.content.Context
import android.os.Bundle
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
 *
 * 系统 API 里 `UtteranceProgressListener` 的抽象方法是 `onError(String)`，
 * `onError(String, Int)` 是另一个可覆盖的方法，所以两个都要写，否则编译不过。
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

    /** 系统里装的语音引擎。名字取自 `EngineInfo.name`（引擎包名）。 */
    val engines: List<TextToSpeech.EngineInfo>
        get() = tts?.engines?.orEmpty() ?: emptyList()

    /**
     * 有没有可用的本地（离线）语音模型。
     *
     * 判据是中文语音数据是否装在本机：`isLanguageAvailable` 返回 `CHECK_VOICE_DATA_PASS`
     * 就说明不必联网。有些机器上这个方法不稳，再退一步看 voices 里有没有中文。
     */
    val hasLocalEngine: Boolean
        get() {
            val t = tts ?: return false
            try {
                if (t.isLanguageAvailable(Locale.CHINA) == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
                    return true
                }
            } catch (_: Exception) {
            }
            return try {
                t.voices.any { it.locale.language == "zh" }
            } catch (_: Exception) {
                false
            }
        }

    private var tts: TextToSpeech? = null
    private var onSpeakDone: (() -> Unit)? = null
    private var onSpeakError: ((String) -> Unit)? = null
    private var onSynthDone: ((Boolean) -> Unit)? = null

    /**
     * 唯一的进度监听器，按 utterance id 派发。
     *
     * 扬声器朗读用固定 id [UTTERANCE_ID]，合成到文件用 `hw_` 前缀的 id，互不干扰，
     * 因此整个引擎只需要一个 listener，不用反复 set。
     */
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            when (utteranceId) {
                UTTERANCE_ID -> {
                    onSpeakDone?.invoke()
                    onSpeakDone = null
                    onSpeakError = null
                }
                else -> if (utteranceId != null && utteranceId.startsWith(SYNTH_PREFIX)) {
                    onSynthDone?.invoke(true)
                    onSynthDone = null
                }
            }
        }

        override fun onError(utteranceId: String?) {
            when (utteranceId) {
                UTTERANCE_ID -> {
                    onSpeakError?.invoke("语音引擎出错")
                    onSpeakDone = null
                    onSpeakError = null
                }
                else -> if (utteranceId != null && utteranceId.startsWith(SYNTH_PREFIX)) {
                    onSynthDone?.invoke(false)
                    onSynthDone = null
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "utterance error code=$errorCode")
            onError(utteranceId)
        }
    }

    @Synchronized
    fun init() {
        if (tts != null) return
        tts = TextToSpeech(app.applicationContext, this)
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
        t.setOnUtteranceProgressListener(progress)
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
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val id = "${SYNTH_PREFIX}${System.currentTimeMillis()}"
        onSynthDone = { ok -> onDone(ok) }
        // 合成是异步的，调用本身只表示「开始了」；结果从 listener 回来。
        val code = t.synthesizeToFile(text, Bundle(), file, id)
        if (code != TextToSpeech.SUCCESS) {
            onSynthDone = null
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
        onSpeakDone = null
        onSpeakError = null
        onSynthDone = null
    }

    fun localeTag(): String = LocaleList.getDefault().get(0)?.language ?: "zh-CN"

    private companion object {
        const val TAG = "HomeworkTts"
        const val UTTERANCE_ID = "homework_tts"
        const val SYNTH_PREFIX = "hw_"
    }
}
