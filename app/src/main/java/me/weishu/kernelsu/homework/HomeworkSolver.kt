package me.weishu.kernelsu.homework

import android.content.Context
import android.net.Uri
import me.weishu.kernelsu.KernelSUApplication

/** 拍题 → 调 AI → 拿答案。悬浮窗和主页共用这条链路。 */
object HomeworkSolver {

    /**
     * 把 AI 返回的整份答案按 `#第N题` 标记切成单题。
     *
     * [Prompt.solve] 要求模型每题前面单独一行 `#第N题`，但客户端如果不切，悬浮窗上就会
     * 显示一堆标记行，而且朗读会把整份作业一口气念完。切完之后每题单独显示、单独朗读。
     *
     * 模型没按格式输出时（只有一段），原样返回一段，不会拆坏。
     */
    fun splitAnswer(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (line in text.lines()) {
            if (MARK_RE.matches(line.trim())) {
                if (current.isNotEmpty()) out.add(current.toString().trim())
                current.setLength(0)
            } else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString().trim())
        val cleaned = out.filter { it.isNotEmpty() }
        return if (cleaned.size > 1) cleaned else listOf(text)
    }

    private val MARK_RE = Regex(Regex.escape(Prompt.MARK_PREFIX) + "\\s*\\d+\\s*题")

    /**
     * 解题。
     *
     * @param system 提示词，见 [Prompt]；默认走 [Prompt.solve] 的拍题作答。
     * @param userText 额外附在图片前面的文字（作文题的题干），可空。
     */
    fun solve(
        context: Context,
        images: List<Uri>,
        onAnswer: (String) -> Unit,
        onError: (String) -> Unit,
        system: String? = null,
        userText: String = "",
    ) {
        if (images.isEmpty()) {
            onError("还没有拍到图片")
            return
        }
        if (HomeworkStore.get(context, HomeworkStore.K_BASE).isBlank()) {
            onError("还没填 AI 接口地址")
            return
        }
        Thread {
            try {
                val b64 = images.map { ImageUtil.jpegBase64(context, it) }
                val prompt = system ?: Prompt.solve(
                    HomeworkStore.get(context, HomeworkStore.K_GRADE, HomeworkStore.DEF_GRADE),
                    HomeworkStore.get(context, HomeworkStore.K_SUBJECT, HomeworkStore.DEF_SUBJECT),
                    images.size,
                )
                val answer = AiClient.chat(
                    client = (context.applicationContext as KernelSUApplication).okhttpClient,
                    baseUrl = HomeworkStore.get(context, HomeworkStore.K_BASE, AiClient.DEFAULT_BASE),
                    apiKey = HomeworkStore.get(context, HomeworkStore.K_KEY),
                    model = HomeworkStore.get(context, HomeworkStore.K_MODEL, AiClient.DEFAULT_MODEL),
                    system = prompt,
                    userText = userText,
                    imagesB64 = b64,
                )
                onAnswer(answer)
            } catch (e: Exception) {
                onError(e.message ?: "请求失败")
            }
        }.start()
    }
}
