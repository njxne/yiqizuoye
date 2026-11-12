package me.weishu.kernelsu.homework

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OpenAI 兼容的 chat/completions 与 models 客户端。 */
object AiClient {

    const val DEFAULT_BASE = "https://ai.murk.top/v1/chat/completions"
    const val DEFAULT_MODEL = "deepseek-flash"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // ==================== 地址拼装 ====================

    fun chatUrl(baseUrl: String): String {
        val u = normalizeBase(baseUrl)
        return if (u.endsWith("/chat/completions")) u
        else if (u.endsWith("/v1")) u + "/chat/completions"
        else u + "/v1/chat/completions"
    }

    fun modelsUrl(baseUrl: String): String {
        val u = normalizeBase(baseUrl)
        val tail = "/chat/completions"
        val base = if (u.endsWith(tail)) u.substring(0, u.length - tail.length) else u
        return if (base.endsWith("/models")) base
        else if (base.endsWith("/v1")) base + "/models"
        else base + "/v1/models"
    }

    private fun normalizeBase(baseUrl: String): String {
        var u = baseUrl.trim()
        if (u.isEmpty()) throw IllegalArgumentException("还没填接口地址")
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u
        while (u.endsWith("/")) u = u.substring(0, u.length - 1)
        return u
    }

    // ==================== 接口 ====================

    /** 取可用模型列表（GET /v1/models）。 */
    fun listModels(client: OkHttpClient, baseUrl: String, apiKey: String): List<String> {
        val req = Request.Builder()
            .url(modelsUrl(baseUrl))
            .get()
            .header("Accept", "application/json")
            .apply { if (apiKey.isNotBlank()) header("Authorization", bearer(apiKey)) }
            .build()
        val body = execute(client, req)
        val root = Json.parseToJsonElement(body).jsonObject
        errorOf(root, body)?.let { throw IOException(it) }

        val out = ArrayList<String>()
        for (item in root["data"]?.jsonArray ?: emptyList()) {
            val id = item.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            if (!id.isNullOrEmpty()) out.add(id)
        }
        if (out.isEmpty()) throw IOException("接口没有返回模型列表")
        return out
    }

    /** 发一次对话请求，返回答案文本。 */
    fun chat(
        client: OkHttpClient,
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        userText: String,
        imagesB64: List<String>,
        timeoutMs: Long = 180_000L,
    ): String {
        val text = StringBuilder()
        val t = userText.trim()
        if (t.isNotEmpty()) text.append(t)
        if (imagesB64.isNotEmpty()) {
            if (text.length > 0) text.append("\n\n")
            text.append("（下方共 ").append(imagesB64.size).append(" 张图片，按顺序阅读）")
        }
        if (text.isEmpty()) text.append("请按系统指令作答。")

        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text.toString())
            })
            for (b64 in imagesB64) {
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:image/jpeg;base64,$b64")
                    })
                })
            }
        }

        val messages = buildJsonArray {
            if (system.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
            }
            add(buildJsonObject {
                put("role", "user")
                put("content", content)
            })
        }

        val payload = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.2)
            put("stream", false)
        }

        val req = Request.Builder()
            .url(chatUrl(baseUrl))
            .post(Json.encodeToString(payload).toRequestBody(JSON))
            .header("Accept", "application/json")
            .apply { if (apiKey.isNotBlank()) header("Authorization", bearer(apiKey)) }
            .build()

        val body = execute(withTimeouts(client, timeoutMs), req)
        val root = Json.parseToJsonElement(body).jsonObject
        errorOf(root, body)?.let { throw IOException(it) }

        val choices = root["choices"]?.jsonArray
            ?: throw IOException("返回里没有答案：" + brief(body))
        val contentOut = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?: throw IOException("返回里没有答案：" + brief(body))
        val out = asText(contentOut).trim()
        if (out.isEmpty()) throw IOException("模型返回了空答案")
        return out
    }

    // ==================== HTTP ====================

    private fun withTimeouts(client: OkHttpClient, readMs: Long) = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(readMs, TimeUnit.MILLISECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun bearer(apiKey: String) = "Bearer " + apiKey.trim()

    private fun execute(client: OkHttpClient, request: Request): String {
        val response = client.newCall(request).execute()
        try {
            val code = response.code
            val body = response.body?.string().orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code：" + apiMessage(body))
            return body
        } finally {
            response.close()
        }
    }

    private fun errorOf(root: JsonObject, body: String): String? {
        val err = root["error"] ?: return null
        val msg = err.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull
        return "接口报错：" + (msg?.ifEmpty { null } ?: brief(body))
    }

    private fun apiMessage(body: String): String {
        val root = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (_: Throwable) {
            return brief(body)
        }
        val msg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull
        return msg?.ifEmpty { null } ?: brief(body)
    }

    /** content 可能是字符串，也可能是分段数组（部分兼容实现会返回数组）。 */
    private fun asText(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.contentOrNull ?: element.content
        is JsonArray -> element.joinToString("") { asText(it) }
        is JsonObject -> element.values.joinToString("") { asText(it) }
    }

    private fun brief(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length > 200) t.substring(0, 200) + "…" else t
    }
}
