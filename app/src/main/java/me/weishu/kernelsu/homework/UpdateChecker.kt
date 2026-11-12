package me.weishu.kernelsu.homework

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 应用内更新：读 GitHub Releases，走镜像源下载，再调起系统安装页。 */
object UpdateChecker {

    private const val TAG = "HomeworkUpdate"

    /** 发布仓库，改这里就能换源。 */
    const val REPO = "njxne/yiqizuoye"

    /** GitHub 直连在国内经常不通，按顺序试镜像；最后兜底走原地址。 */
    private val MIRRORS = listOf(
        "https://gh-proxy.com/https://github.com/",
        "https://ghfast.top/https://github.com/",
        "https://ghproxy.net/https://github.com/",
        "https://mirror.ghproxy.com/https://github.com/",
    )

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data class Available(val release: Release) : Result
        data object UpToDate : Result
        data class Error(val message: String) : Result
    }

    data class Release(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val fileName: String,
        val notes: String,
    )

    /** 检查更新（后台线程）。 */
    fun check(
        repo: String = REPO,
        currentVersionName: String,
        currentVersionCode: Int,
        onResult: (Result) -> Unit,
    ) {
        Thread {
            try {
                val url = "https://api.github.com/repos/$repo/releases/latest"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "yiqizuoye-app")
                    .get()
                    .build()
                val body = execute(request)
                val root = Json.parseToJsonElement(body).jsonObject

                val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
                    ?: throw IOException("release 里没有 tag_name")
                val assets = root["assets"]?.jsonArray ?: emptyList()
                val asset = assets.mapNotNull { it.jsonObject }
                    .filter { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", true) == true }
                    .maxByOrNull { it["size"]?.jsonPrimitive?.longOrNull ?: 0L }
                    ?: throw IOException("这个 release 里没有 apk")

                val name = asset["name"]?.jsonPrimitive?.contentOrNull
                    ?: throw IOException("没有 apk 文件名")
                val download = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?: throw IOException("没有下载地址")
                val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val code = versionCodeOf(tag)

                val release = Release(tag, code, download, name, notes)
                onResult(if (code > currentVersionCode) Result.Available(release) else Result.UpToDate)
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                onResult(Result.Error(e.message ?: "检查更新失败"))
            }
        }.start()
    }

    /** 下载 apk（后台线程，逐个试镜像）。 */
    fun download(
        context: Context,
        release: Release,
        onProgress: (read: Long, total: Long) -> Unit,
        onDone: (File?, String?) -> Unit,
    ) {
        Thread {
            var lastError: String? = null
            val dir = File(context.filesDir, "update").apply { if (!exists()) mkdirs() }
            for (candidate in candidateUrls(release.downloadUrl)) {
                val target = File(dir, release.fileName)
                try {
                    downloadOne(context, candidate, target, onProgress)
                    if (target.length() > 0L) {
                        onDone(target, null)
                        return@Thread
                    }
                    lastError = "下载到的文件是空的"
                } catch (e: Exception) {
                    Log.w(TAG, "mirror ${candidate.take(40)} failed", e)
                    lastError = e.message ?: "下载失败"
                    target.delete()
                }
            }
            onDone(null, lastError ?: "所有镜像源都下不动")
        }.start()
    }

    /** 调起系统安装页。 */
    fun install(context: Context, apk: File) {
        if (!apk.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "安装更新").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ==================== 内部实现 ====================

    private fun candidateUrls(url: String): List<String> {
        val out = ArrayList<String>()
        if (url.startsWith("https://github.com/")) {
            val rest = url.removePrefix("https://github.com/")
            MIRRORS.forEach { out.add(it + rest) }
        }
        out.add(url)
        return out
    }

    private fun downloadOne(
        context: Context,
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "yiqizuoye-app")
            .get()
            .build()
        val client = apiClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val response = client.newCall(request).execute()
        try {
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("响应体是空的")
            val total = body.contentLength()
            FileOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                body.source().use { src ->
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        } finally {
            response.close()
        }
        // apk 本质是 zip，开头必须是 PK。
        val head = ByteArray(2)
        java.io.FileInputStream(target).use { ins ->
            if (ins.read(head) < 2) throw IOException("下载到的不是 apk")
        }
        if (head[0].toInt() != 'P'.code || head[1].toInt() != 'K'.code) {
            throw IOException("下载到的不是 apk")
        }
    }

    private fun execute(request: Request): String {
        val response = apiClient.newCall(request).execute()
        try {
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}：$body")
            }
            return body
        } finally {
            response.close()
        }
    }

    /** "v1.2.3" / "1.2.3" → 10203，用来比大小。 */
    private fun versionCodeOf(tag: String): Int {
        var code = 0
        var scale = 1
        var digits = 0
        for (m in Regex("\\d+").findAll(tag)) {
            code += (m.value.toIntOrNull() ?: 0) * scale
            scale *= 1000
            if (++digits >= 4) break
        }
        return code
    }
}
