package me.weishu.kernelsu.homework

/**
 * 把 AI 返回的整份答案按 `#第N题` 标记切成单题。
 *
 * [Prompt.solve] 要求模型每题前面单独一行 `#第N题`，但客户端不切的话，悬浮窗上就是一堆
 * 标记行，朗读还会把整份作业一口气念完。切完之后每题单独显示、单独朗读。
 *
 * 模型没按格式输出时（只有一段），去掉标记行、原样保留正文，不会拆坏内容。
 * 万一整份答案只有标记行没有正文，返回空列表——总比在悬浮窗上糊一排 `#第N题` 强。
 *
 * 这里刻意不放任何 Android 依赖，好让它能脱离设备单独跑测试。
 */
object AnswerSplitter {

    fun split(raw: String): List<String> {
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
        if (cleaned.size > 1) return cleaned
        // 模型没按格式输出：只留一段，去掉标记行，正文原样保留。
        val body = text.lines().filterNot { MARK_RE.matches(it.trim()) }.joinToString("\n").trim()
        return if (body.isEmpty()) emptyList() else listOf(body)
    }

    private val MARK_RE = Regex(Regex.escape(Prompt.MARK_PREFIX) + "\\s*\\d+\\s*题")
}
