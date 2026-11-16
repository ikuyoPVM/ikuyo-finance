package xyz.ikuyo.finance.ui.notice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.ikuyo.finance.core.NoticeKind

/**
 * 应用内提示的统一外观。
 *
 * 设计约束（对应「提示消息系统」要求）：
 * - 一条消息只说一件事，最长三行，超出省略，避免遮挡内容；
 * - 分级只靠颜色与圆点区分，不用 emoji、不用感叹号堆砌；
 * - 可撤销的消息停留更久并给出唯一的文字动作，其余自动消失；
 * - 圆角、描边、内边距固定，深色主题下用同色低透明度叠加，保证对比度。
 */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        AppNoticeBar(data = data)
    }
}

@Composable
private fun AppNoticeBar(data: SnackbarData) {
    val visuals = data.visuals as? NoticeVisuals
    val kind = visuals?.kind ?: NoticeKind.Info
    val accent = kind.accent()
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    val container = accent
        .copy(alpha = if (dark) 0.22f else 0.10f)
        .compositeOver(MaterialTheme.colorScheme.surface)
    val action = data.visuals.actionLabel

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = container,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accent, CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.visuals.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!action.isNullOrBlank()) {
                TextButton(onClick = { data.performAction() }) {
                    Text(text = action, color = accent, style = MaterialTheme.typography.labelLarge)
                }
            } else if (data.visuals.withDismissAction) {
                TextButton(onClick = { data.dismiss() }) {
                    Text(text = "关闭", color = accent, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** 携带分级的一次性提示内容，交给 [AppSnackbarHost] 渲染。 */
data class NoticeVisuals(
    override val message: String,
    val kind: NoticeKind = NoticeKind.Success,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = if (actionLabel != null) {
        SnackbarDuration.Long
    } else {
        SnackbarDuration.Short
    },
) : SnackbarVisuals

/**
 * 展示一条提示，并抑制 1.2 秒内完全相同的内容。
 *
 * 连点按钮、同一错误被两处同时上报时，用户只会看到一条，不会重复弹出。
 */
suspend fun SnackbarHostState.showNotice(
    text: String,
    kind: NoticeKind = NoticeKind.Success,
    actionLabel: String? = null,
    withDismissAction: Boolean = kind == NoticeKind.Error,
): SnackbarResult? {
    if (!NoticeThrottle.allow(text)) return null
    return showSnackbar(
        NoticeVisuals(
            message = text,
            kind = kind,
            actionLabel = actionLabel,
            withDismissAction = withDismissAction,
        ),
    )
}

/** 展示一条来自业务层的事件，撤销动作统一叫「撤销」。 */
suspend fun SnackbarHostState.showEvent(
    event: xyz.ikuyo.finance.core.UiEvent.Message,
    undoLabel: String = "撤销",
): SnackbarResult? = showNotice(
    text = event.text,
    kind = event.kind,
    actionLabel = if (event.undoable) undoLabel else null,
)

/** 记住最近一次提示，用于抑制重复内容。 */
@Stable
object NoticeThrottle {
    private const val WINDOW_MILLIS = 1200L
    private var lastText: String? = null
    private var lastAt: Long = 0L

    @Synchronized
    fun allow(text: String): Boolean {
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastAt < WINDOW_MILLIS) return false
        lastText = text
        lastAt = now
        return true
    }
}

/** 便捷入口：在 Composable 里记住宿主状态。 */
@Composable
fun rememberNoticeHost(): SnackbarHostState = remember { SnackbarHostState() }

private fun NoticeKind.accent(): Color = when (this) {
    NoticeKind.Success -> Color(0xFF2E7D5B)
    NoticeKind.Info -> Color(0xFF3A6EA5)
    NoticeKind.Warning -> Color(0xFF9A6A00)
    NoticeKind.Error -> Color(0xFFA03A34)
}

private fun Color.luminanceIsDark(): Boolean {
    val value = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return value < 0.5f
}
