package me.weishu.kernelsu.homework

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
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

    fun hasRoot(): Boolean = runCatching { Shell.getShell().isRoot }.getOrDefault(false)

    fun injectAsync(context: Context, audio: File, onState: (Result) -> Unit) {
        Thread { inject(audio, onState) }.start()
    }

    /** 停掉正在往虚拟设备里写的进程。 */
    fun stop() {
        val shell = rootShell() ?: return
        ShellUtils.fastCmd(shell, "pkill -f 'tinyplay|ffmpeg|/dev/snd/loopback' 2>/dev/null")
        ShellUtils.fastCmd(shell, "pkill -f 'cat > /dev/snd/hw' 2>/dev/null")
    }

    private fun inject(audio: File, onState: (Result) -> Unit) {
        if (!audio.exists() || audio.length() <= 0L) {
            onState(Result(State.FAILED, "朗读音频是空的，没法注入"))
            return
        }
        val shell = rootShell()
        if (shell == null) {
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
        if (!routeMicToLoopback(shell, card)) {
            onState(Result(
                State.FAILED,
                "找到了虚拟声卡，但机器上没有能切的麦克风控制项，声音只能从扬声器出来",
            ))
            return
        }
        onState(Result(State.INJECTING, "正在把朗读音频写进虚拟麦克风…（「$targetPackage」得正在录音，收不到会自动改成扬声器念）"))
        if (playInto(shell, card, audio)) {
            onState(Result(
                State.DONE,
                "声音已灌进虚拟麦克风。它只对正在录音的 App 有效——这次「$targetPackage」要是没在录音就收不到，" +
                    "那就先在那边点开始朗读，再回来点一次朗读",
            ))
        } else {
            onState(Result(
                State.FAILED,
                "写入虚拟麦克风失败（多半是「$targetPackage」没在录音，写进去没人读就堵死了），声音改成从扬声器念",
            ))
        }
    }

    /** 拿一个能用的 root shell；拿不到或不是 root 就返回 null。 */
    private fun rootShell(): Shell? =
        runCatching { Shell.getShell() }.getOrNull()?.takeIf { it.isRoot }

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
        return findLoopbackCard(shell)
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
        // 麦克风源的开关在主板那张卡上，aloop 虚拟卡自己一般没有混音控制项。
        // 列清单和写开关必须用同一张卡，不然就是在 aloop 卡上改一个根本不存在的开关。
        val ctlCard = mainCard(shell) ?: 0
        val listing = ShellUtils.fastCmd(shell, "tinymix -c $ctlCard 2>/dev/null")
        val controls = listing.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (controls.isEmpty()) return false

        val lower = controls.map { it.lowercase() }
        for (candidate in MIC_SOURCE_CONTROLS) {
            val idx = lower.indexOf(candidate.lowercase())
            if (idx < 0) continue
            // 该控制项的值：优先试虚拟声卡名，再试常见的「Mic reference」类值。
            for (value in listOf("Loopback", "snd-aloop", "Mic reference", "Mic Reference", "loopback")) {
                val ctl = controls[idx]
                if (ShellUtils.fastCmdResult(shell, "tinymix -c $ctlCard '$ctl' '$value' 2>/dev/null")) {
                    Log.i(TAG, "mic source (card $ctlCard) -> $ctl = $value")
                    return true
                }
            }
        }
        return false
    }

    /** 主板声卡的编号：`/proc/asound/cards` 里第一张不是 loopback 的卡。 */
    private fun mainCard(shell: Shell): Int? {
        val cards = ShellUtils.fastCmd(shell, "cat /proc/asound/cards 2>/dev/null")
        for (line in cards.lines()) {
            val t = line.trim()
            if (t.isEmpty() || !t.contains(":", false)) continue
            val name = t.substringAfter(':').trim().lowercase()
            if (name.contains("loopback") || name.contains("aloop")) continue
            return t.substringBefore(':').trim().substringBefore('-').trim().toIntOrNull()
        }
        return null
    }

    private fun playInto(shell: Shell, card: Int, audio: File): Boolean {
        val dev = "/dev/snd/hw$card,0"
        val path = audio.absolutePath
        // 超时按音频时长算，别写死：s16le / 44.1kHz / 立体声是 176400 字节每秒，
        // 目标 App 在录音时写完大约要这么久；它没在录音时写会一直堵在缓冲区上，靠超时兜底。
        // Android 9 的 toybox 自带 timeout。
        val seconds = (audio.length() - 44).coerceAtLeast(0L) / 176_400L + 15L
        // snd-aloop 默认是 16bit / 44.1kHz / 立体声，先按这个格式灌。
        if (ShellUtils.fastCmdResult(shell, "command -v ffmpeg 2>/dev/null")) {
            val direct =
                "timeout $seconds ffmpeg -nostdin -y -i '$path' -f s16le -acodec pcm_s16le -ar 44100 -ac 2 '$dev' 2>/dev/null"
            if (ShellUtils.fastCmdResult(shell, direct)) return true
            val piped =
                "timeout $seconds sh -c \"ffmpeg -nostdin -y -i '$path' -f s16le -acodec pcm_s16le -ar 44100 -ac 2 - 2>/dev/null | cat > '$dev'\""
            if (ShellUtils.fastCmdResult(shell, piped)) return true
        }
        // 没有 ffmpeg 就硬把 WAV 头剥掉直接写裸 PCM（头部长度因文件而异，44 是常见值）。
        val raw =
            "timeout $seconds sh -c \"dd if='$path' bs=44 skip=1 2>/dev/null | cat > '$dev'\""
        return ShellUtils.fastCmdResult(shell, raw)
    }
}
