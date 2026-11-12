package me.weishu.kernelsu.ui.util

import android.annotation.SuppressLint
import android.os.Build

object PinyinUtil {

    /**
     * `android.icu.text.Transliterator` only exists from API 29. The transliteration is done in
     * a separate class so that nothing on older platforms ever loads it.
     */
    @Synchronized
    fun toPinyin(input: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return input
        }
        return IcuTransliterator.toPinyin(input)
    }
}

@SuppressLint("NewApi")
private object IcuTransliterator {

    private val transliterator =
        android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")

    fun toPinyin(input: String): String =
        transliterator.transliterate(input).replace(" ", "")
}
