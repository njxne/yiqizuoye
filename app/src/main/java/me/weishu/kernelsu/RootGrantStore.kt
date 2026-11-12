package me.weishu.kernelsu

import android.content.Context

/**
 * Per-app root grants for the app-level fake root.
 *
 * The manager can only pretend to be rooted, so there is no kernel profile store to write a grant
 * into. Instead each package's grant decision is kept in app-private preferences, keyed by package
 * name. A grant therefore survives the manager being restarted or rebooted, and disappears when the
 * app is uninstalled - which is what a real per-app grant looks like from inside the manager.
 *
 * Like real KernelSU, an app is **not** granted root until it is explicitly allowed, and revoking
 * the grant takes effect immediately and sticks.
 */
object RootGrantStore {
    private const val PREFS = "root_grants"
    private const val KEY_PREFIX = "grant_"

    fun isGranted(packageName: String): Boolean = runCatching {
        ksuApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFIX + packageName, false)
    }.getOrDefault(false)

    fun setGranted(packageName: String, granted: Boolean) {
        runCatching {
            ksuApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PREFIX + packageName, granted)
                .apply()
        }
    }
}
