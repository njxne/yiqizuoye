package me.weishu.kernelsu.ui.component.liquidglass

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import me.weishu.kernelsu.ui.component.bottombar.BottomBarDestination
import me.weishu.kernelsu.ui.component.bottombar.NavigationBadgeState
import me.weishu.kernelsu.ui.component.bottombar.navigationBadgeFor
import top.yukonga.miuix.kmp.basic.BadgedBox

private data class LiquidTabItem(
    val label: String,
    val icon: ImageVector,
)

@Composable
fun LiquidGlassBottomBar(
    selectedIndex: () -> Int,
    onSelected: (Int) -> Unit,
    backdrop: Backdrop,
    navigationBadge: NavigationBadgeState,
    modifier: Modifier = Modifier,
) {
    val items = BottomBarDestination.entries.map { destination ->
        LiquidTabItem(
            label = stringResource(destination.label),
            icon = destination.icon,
        )
    }

    LiquidBottomTabs(
        selectedTabIndex = selectedIndex,
        onTabSelected = onSelected,
        backdrop = backdrop,
        tabsCount = items.size,
        modifier = modifier,
    ) {
        items.forEachIndexed { index, item ->
            LiquidBottomTab({ onSelected(index) }) {
                val badge = navigationBadgeFor(index, navigationBadge, floating = true)
                if (badge != null) {
                    BadgedBox(badge = { badge() }) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                } else {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = item.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}
