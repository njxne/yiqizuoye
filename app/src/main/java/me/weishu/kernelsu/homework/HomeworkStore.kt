package me.weishu.kernelsu.homework

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 作业模块的设置、答题统计与历史记录（SharedPreferences）。 */
object HomeworkStore {

    private const val NAME = "homework"
    private const val MAX_HISTORY = 50
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    const val K_BASE = "base_url"
    const val K_KEY = "api_key"
    const val K_MODEL = "model"
    const val K_GRADE = "grade"
    const val K_SUBJECT = "subject"
    const val K_CORRECT = "correct_count"
    const val K_TOTAL = "total_count"
    const val K_STREAK = "streak_count"
    const val K_HISTORY = "history"
    const val K_FLOAT = "float_enabled"
    const val K_SPEAKER_FALLBACK = "speaker_fallback"
    const val K_LAST_ANSWERED = "last_answered"
    const val K_ESSAY_TOPIC = "essay_topic"

    const val DEF_BASE = AiClient.DEFAULT_BASE
    const val DEF_MODEL = AiClient.DEFAULT_MODEL
    const val DEF_GRADE = "三年级"
    const val DEF_SUBJECT = "自动识别"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun get(context: Context, key: String, def: String = ""): String =
        prefs(context).getString(key, def).orEmpty()

    fun set(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun getInt(context: Context, key: String, def: Int = 0): Int = prefs(context).getInt(key, def)

    fun setInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
    }

    fun getBool(context: Context, key: String, def: Boolean = false): Boolean =
        prefs(context).getBoolean(key, def)

    fun setBool(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    // ==================== 答题统计 ====================

    @Serializable
    data class Stats(
        val total: Int,
        val correct: Int,
        val streak: Int,
    ) {
        val rate: Double
            get() = if (total == 0) 0.0 else correct.toDouble() / total
    }

    fun stats(context: Context): Stats = Stats(
        total = prefs(context).getInt(K_TOTAL, 0),
        correct = prefs(context).getInt(K_CORRECT, 0),
        streak = prefs(context).getInt(K_STREAK, 0),
    )

    /** 记一次答题。连续答对且与上一次间隔不足一天时累计连对。 */
    fun markAnswer(context: Context, correct: Boolean) {
        val p = prefs(context)
        val total = p.getInt(K_TOTAL, 0) + 1
        val correctCount = p.getInt(K_CORRECT, 0) + if (correct) 1 else 0
        val now = System.currentTimeMillis()
        val last = p.getLong(K_LAST_ANSWERED, 0L)
        val streak = if (correct) {
            if (now - last < DAY_MS) p.getInt(K_STREAK, 0) + 1 else 1
        } else {
            0
        }
        p.edit()
            .putInt(K_TOTAL, total)
            .putInt(K_CORRECT, correctCount)
            .putInt(K_STREAK, streak)
            .putLong(K_LAST_ANSWERED, now)
            .apply()
    }

    fun resetStats(context: Context) {
        prefs(context).edit()
            .putInt(K_TOTAL, 0)
            .putInt(K_CORRECT, 0)
            .putInt(K_STREAK, 0)
            .apply()
    }

    // ==================== 历史 ====================

    @Serializable
    data class Record(
        val time: Long,
        val subject: String,
        val grade: String,
        val question: String,
        val answer: String,
        val correct: Boolean,
        val image: String,
    )

    fun history(context: Context): List<Record> = runCatching {
        json.decodeFromString<List<Record>>(get(context, K_HISTORY, "[]"))
    }.getOrDefault(emptyList())

    fun addRecord(context: Context, record: Record) {
        val list = history(context).toMutableList()
        list.add(0, record)
        while (list.size > MAX_HISTORY) {
            deleteThumb(list.removeAt(list.size - 1))
        }
        set(context, K_HISTORY, json.encodeToString(list))
    }

    fun removeRecord(context: Context, time: Long) {
        val list = history(context).toMutableList()
        val removed = list.find { it.time == time }
        list.removeAll { it.time == time }
        removed?.let { deleteThumb(it) }
        set(context, K_HISTORY, json.encodeToString(list))
    }

    fun clearHistory(context: Context) {
        history(context).forEach { deleteThumb(it) }
        set(context, K_HISTORY, "[]")
    }

    fun thumbDir(context: Context): File = File(context.filesDir, "homework_thumbs").apply {
        if (!exists()) mkdirs()
    }

    fun thumbFile(context: Context, name: String): File = File(thumbDir(context), name)

    private fun deleteThumb(record: Record) {
        if (record.image.isBlank()) return
        runCatching { File(record.image).delete() }
    }
}
