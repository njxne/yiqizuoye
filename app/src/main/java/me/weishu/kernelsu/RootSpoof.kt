package me.weishu.kernelsu

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-level fake root state.
 *
 * Nothing here grants real root. It only makes the manager *report* a rooted device so the
 * UI shows the "root obtained" state. The flag lives in app-private preferences, so it is
 * gone when the app is uninstalled and a fresh install has to jailbreak again.
 */
object RootSpoof {
    private const val PREFS = "settings"
    private const val KEY = "root_spoofed"

    /** Bumped whenever the fake root is toggled so the UI can react without a restart. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun isEnabled(): Boolean = runCatching {
        ksuApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }.getOrDefault(false)

    fun setEnabled(enabled: Boolean) {
        runCatching {
            ksuApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY, enabled)
                .apply()
        }
        applyToNatives(enabled)
        _revision.value = _revision.value + 1
    }

    /**
     * Re-applies the fake root to the native side. The native flag is process-local, so a new
     * process has to be told again that the app considers itself rooted.
     */
    fun syncNative() {
        applyToNatives(isEnabled())
    }

    private fun applyToNatives(enabled: Boolean) {
        runCatching { Natives.setRootSpoofedNative(enabled, BuildConfig.VERSION_CODE) }
    }
}
