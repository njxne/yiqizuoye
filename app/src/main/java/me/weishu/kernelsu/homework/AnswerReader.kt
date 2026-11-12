package me.weishu.kernelsu.homework

import android.content.Context
import java.io.File

/**
 * 朗读一条答案。
 *
 * 流程：用本地语音模型合成 → 试着灌进目标 App 的麦克风 → 灌不进去就从扬声器念出来。
 * 悬浮窗和主页共用这一份逻辑，避免两处的朗读行为不一致（早先主页只念扬声器，
 * 悬浮窗才会尝试注入，用户在主页点「朗读」就以为功能坏了）。
 */
object AnswerReader {

    interface Listener {
        /** 已经开始念了（无论走麦克风还是扬声器）。 */
        fun onStart() {}

        /** 念完了。 */
        fun onDone() {}

        /** 没念成，[message] 是原因。 */
        fun onError(message: String) {}
    }

    @Volatile
    private var busy = false

    fun isBusy(): Boolean = busy

    /** 打断正在念的内容，并停掉往虚拟麦克风里写数据的进程。 */
    fun stop(tts: TtsEngine) {
        MicInjector.stop()
        tts.stop()
        busy = false
    }

    /**
     * 朗读 [text]。
     *
     * @return true 表示已经接下了这件事（结果走 [Listener]）；false 表示没接（没内容、
     *         正在念别的、或者引擎没就绪）。
     */
    fun read(context: Context, tts: TtsEngine, text: String, listener: Listener? = null): Boolean {
        val t = text.trim()
        if (t.isEmpty()) {
            listener?.onError("没有内容可读")
            return false
        }
        if (busy) {
            listener?.onError("正在朗读上一条，先听完")
            return false
        }
        busy = true
        listener?.onStart()
        Thread {
            MicInjector.targetPackage =
                HomeworkStore.get(context, "target_package", MicInjector.targetPackage)
            val file = File(context.filesDir, "tts_${System.currentTimeMillis()}.wav")
            tts.awaitReady(5000)
            tts.synthesizeToFile(t, file) { ok ->
                if (ok) {
                    MicInjector.injectAsync(context, file) { r ->
                        if (r.ok) {
                            busy = false
                            listener?.onDone()
                        } else {
                            speak(tts, t, listener)
                        }
                    }
                } else {
                    speak(tts, t, listener)
                }
            }
        }.start()
        return true
    }

    /** 回退方案：直接从扬声器念。 */
    private fun speak(tts: TtsEngine, text: String, listener: Listener?) {
        tts.speak(text, object : TtsEngine.Listener {
            override fun onDone() {
                busy = false
                listener?.onDone()
            }

            override fun onError(message: String) {
                busy = false
                listener?.onError(message)
            }
        })
    }
}
