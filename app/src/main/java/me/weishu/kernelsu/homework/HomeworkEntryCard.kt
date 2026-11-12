package me.weishu.kernelsu.homework

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 首页的作业入口卡片。 */
@Composable
fun HomeworkEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 不缓存：每次重组都读一次 SharedPreferences，答对计数变了卡片立刻跟着变。
    val stats = HomeworkStore.stats(context)

    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("一起作业", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "悬浮窗拍题，AI 直接给答案，还能朗读",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "答对 ${stats.correct} / ${stats.total}" + if (stats.streak > 1) "　连对 ${stats.streak}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
