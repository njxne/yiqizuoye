package me.weishu.kernelsu.ui.component.liquidglass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * Backdrop source for the liquid glass bottom bar.
 *
 * Uses the official Kyant0 backdrop library (minSdk 21, with runtime guards for
 * RenderEffect/RuntimeShader) instead of the MIUIX blur library, which declares minSdk 33
 * and is not safe to draw on Android 9.
 */
@Composable
fun rememberLiquidLayerBackdrop(
    onDraw: ContentDrawScope.() -> Unit,
): LayerBackdrop = rememberLayerBackdrop(onDraw = onDraw)

fun Modifier.liquidLayerBackdrop(backdrop: LayerBackdrop): Modifier = layerBackdrop(backdrop)
