package me.weishu.kernelsu.ui.component.rebootoverlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.RebootSimulator

/**
 * Full-screen "rebooting" screen shown while [RebootSimulator] replaces the process. It sits above
 * the navigation content, so it is visible no matter which page the restart was requested from.
 */
@Composable
fun RebootOverlay() {
    val rebooting by RebootSimulator.rebooting.collectAsStateWithLifecycle()
    if (!rebooting) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.rebooting),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.reboot_simulated_notice),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
