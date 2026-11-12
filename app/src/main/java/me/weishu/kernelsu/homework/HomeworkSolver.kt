package me.weishu.kernelsu.homework

import android.content.Context
import android.net.Uri
import me.weishu.kernelsu.KernelSUApplication

/** 拍题 → 调 AI → 拿答案。悬浮窗和主页共用这条链路。 */
object HomeworkSolver {

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
