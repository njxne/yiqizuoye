package me.weishu.kernelsu.ui.util

import android.content.Intent
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.weishu.kernelsu.ksuApp

/**
 * App-level stand-in for a device reboot.
 *
 * Android only lets a rooted process - or one holding the signature-only `REBOOT` permission - ask
 * PowerManagerService to reboot, so on a device without real root every restart button in the
 * manager would silently do nothing. This instead restarts the manager itself: the fake root lives
 * in app-private preferences, so it is still there when the app comes back up, which is exactly
 * what a real reboot of a rooted device looks like from inside the app.
 *
 * [RebootOverlay] is expected to show while [rebooting] is true.
 */
object RebootSimulator {

    /** How long the "rebooting" screen stays up before the process is replaced. */
    private const val REBOOT_SCREEN_MILLIS = 1500L

    private val _rebooting = MutableStateFlow(false)
    val rebooting: StateFlow<Boolean> = _rebooting.asStateFlow()

    fun begin() {
        if (_rebooting.value) return
        _rebooting.value = true
        Thread {
            try {
                Thread.sleep(REBOOT_SCREEN_MILLIS)
            } catch (_: InterruptedException) {
            }
            restartApp()
        }.start()
    }

    private fun restartApp() {
        val intent = ksuApp.packageManager.getLaunchIntentForPackage(ksuApp.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            runCatching { ksuApp.startActivity(intent) }
        }
        // Give the activity manager a moment to schedule the new activity before this process goes
        // away; otherwise the clear-task intent would die with it.
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
        }
        Process.killProcess(Process.myPid())
    }
}
