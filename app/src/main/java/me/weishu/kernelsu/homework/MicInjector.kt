package me.weishu.kernelsu.homework

import android.content.Context
import android.util.Log
import com.github.topjohnwu.libsu.core.Shell
import com.github.topjohnwu.libsu.service.ShellUtils
import java.io.File

/**
 * 把朗读音频「灌进」目标 App（一起作业）的麦克风。
 *
 * 思路：root + `snd-aloop` 内核模块虚拟出一张声卡，把 TTS 音频写进它的 playback，
 * 再用 `tinymix` 把主板的麦克风输入源切到这张虚拟声卡上。目标 App 走的是系统默认
 * 录音通道，切源之后它读到的就是虚拟设备里的音频。
 *
 * 这条路依赖内核带 `snd-aloop`、且机器上有能改麦克风源的 ALSA 控制项。不满足时
 * [injectAsync] 会如实回报失败，由调用方回退到扬声器播放。
 */
object MicInjector {

    enum class State {
        UNKNOWN,
        NO_ROOT,
        NO_LOOPBACK,
        ROUTED,
        INJECTING,
        DONE,
        FAILED,
    }

    data class Result(val state: State, val message: String) {
        val ok: Boolean get() = state == State.DONE
    }

    private const val TAG = "HomeworkMic"

    /** 目标 App 包名，可在设置里改。 */
    var targetPackage: String = "com.yiqizuoye"

    /** 已知存在过的麦克风源控制项名，逐个试。 */
    private val MIC_SOURCE_CONTROLS = listOf(
        "Mic Capture Source", "Input Source", "Mic Source", "Digital Mic",
        "Voice Capture Source", "Capture Source", "Analog Mic", "Mic Switch",
        "Capture Switch", "Mic Reference", "Voice Mic", "Main Mic",
    )

    fun hasRoot(): Boolean = runCatching { Shell.getInstance().isRooted }.getOrDefault(false)

    fun injectAsync(context: Context, audio: File, onState: (Result) -> Unit) {
        Thread { inject(audio, onState) }.start()
    }

    /** 停掉正在往虚拟设备里写的进程。 */
    fun stop() {
        val shell = Shell.getInstance()
        if (!shell.isRooted) return
        ShellUtils.fastCmd(shell, "pkill -f 'tinyplay|ffmpeg|/dev/snd/loopback' 2>/dev/null")
        ShellUtils.fastCmd(shell, "pkill -f 'cat > /dev/snd/hw' 2>/dev/null")
    }

    private fun inject(audio: File, onState: (Result) -> Unit) {
        if (!audio.exists() || audio.length() <= 0L) {
            onState(Result(State.FAILED, "朗读音频是空的，没法注入"))
            return
        }
        val shell = Shell.getInstance()
        if (!shell.isRooted) {
            onState(Result(State.NO_ROOT, "没有 root，声音只能从扬声器出来，对方 App 听不到"))
            return
        }
        val card = ensureLoopback(shell)
        if (card == null) {
            onState(Result(
                State.NO_LOOPBACK,
                "内核没有 snd-aloop 模块，虚拟不出麦克风。可以在有 root 的机器上手动加载后再试",
            ))
            return
        }
        routeMicToLoopback(shell, card)
        onState(Result(State.ROUTED, "已把麦克风切到虚拟音频设备（card $card）"))
        onState(Result(State.INJECTING, "正在把朗读音频写进虚拟麦克风…"))
        val ok = playInto(shell, card, audio)
        if (ok) {
            onState(Result(State.DONE, "声音已灌进麦克风，去「$targetPackage」里点开始朗读"))
        } else {
            onState(Result(State.FAILED, "写入虚拟麦克风失败，已回退到扬声器播放"))
        }
    }

    // ==================== 虚拟声卡 ====================

    private fun ensureLoopback(shell: Shell): Int? {
        findLoopbackCard(shell)?.let { return it }
        val candidates = listOf(
            "modprobe snd-aloop",
            "insmod /vendor/lib/modules/snd-aloop.ko",
            "insmod /system/lib/modules/snd-aloop.ko",
            "insmod /lib/modules/snd-aloop.ko",
            "insmod /data/data/me.weishu.kernelsu/modules/snd-aloop.ko",
        )
        for (cmd in candidates) {
            if (ShellUtils.fastCmdResult(shell, cmd)) {
                Log.i(TAG, "loopback loaded via: $cmd")
                findLoopbackCard(shell)?.let { return it }
            }
        }
        // 有些内核把 aloop 编进内核了，只是设备节点没列出来；再查一次。
        findLoopbackCard(shell)
    }

    private fun findLoopbackCard(shell: Shell): Int? {
        val cards = ShellUtils.fastCmd(shell, "cat /proc/asound/cards 2>/dev/null")
        for (line in cards.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (!t.contains(":", false)) continue
            val name = t.substringAfter(':').trim().lowercase()
            if (!name.contains("loopback") && !name.contains("aloop")) continue
            val idx = t.substringBefore(':').trim().substringBefore('-').trim()
            val card = idx.toIntOrNull()
            if (card != null) return card
        }
        return null
    }

    private fun routeMicToLoopback(shell: Shell, card: Int): Boolean {
        val listing = ShellUtils.fastCmd(
            shell,
            "tinymix -c $card 2>/dev/null || tinymix 2>/dev/null || tinymix -l 2>/dev/null",
        )
        val controls = listing.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (controls.isEmpty()) return false

        val lower = controls.map { it.lowercase() }
        for (candidate in MIC_SOURCE_CONTROLS) {
            val idx = lower.indexOf(candidate.lowercase())
            if (idx < 0) continue
            // 该控制项的值：优先试虚拟声卡名，再试常见的「Mic reference」类值。
            for (value in listOf("Loopback", "snd-aloop", "Mic reference", "Mic Reference", "loopback")) {
                if (ShellUtils.fastCmdResult(shell, "tinymix -c $card '$candidate' '$value' 2>/dev/null")) {
                    Log.i(TAG, "mic source -> $candidate = $value")
                    return true
                }
            }
        }
        return false
    }

    private fun playInto(shell: Shell, card: Int, audio: File): Boolean {
        val dev = "/dev/snd/hw$card,0"
        val path = audio.absolutePath
        if (ShellUtils.fastCmdResult(shell, "command -v ffmpeg 2>/dev/null")) {
            val cmd = "ffmpeg -nostdin -y -i '$path' -f wav -acodec pcm_s16le -ar 44100 -ac 2 '$dev'"
            if (ShellUtils.fastCmdResult(shell, cmd)) return true
        }
        // 去掉 WAV 头直接写裸 PCM（snd-aloop 默认 16bit 立体声，采样率自适应）。
        val raw = "dd if='$path' bs=44 skip=1 2>/dev/null | cat > '$dev'"
        if (ShellUtils.fastCmdResult(shell, raw)) return true
        return false
    }
}
