package xyz.ikuyo.finance.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.ui.notice.AppSnackbarHost
import xyz.ikuyo.finance.ui.notice.showEvent
import xyz.ikuyo.finance.domain.model.AiChatMessage
import xyz.ikuyo.finance.domain.model.BillCandidate
import xyz.ikuyo.finance.domain.model.ChatRole
import xyz.ikuyo.finance.ui.components.BillEntryForm
import xyz.ikuyo.finance.ui.theme.amountColorFor

/**
 * AI 记账页：对话模式负责理解自然语言，记账模式仍然走本地表单。
 *
 * 设计约束（对应阶段三计划书第 5、9 章）：
 * - 只有用户确认（或置信度达标且无歧义）的候选账单才会写入数据库；
 * - 请求进行中不阻塞本地记账，可随时停止，失败保留原文可重试；
 * - 页面顶部常显当前模型与备用状态，避免「不知道在跟谁说话」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiScreen(
    onBackToHome: () -> Unit = {},
    onOpenBills: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    viewModel: AiChatViewModel = viewModel(factory = AiChatViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is UiEvent.Message) snackbarHostState.showEvent(event)
        }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AiHeader(
                state = state,
                onOpenSettings = onOpenAiSettings,
                onClear = viewModel::clearChat,
            )
            ModeSwitch(mode = state.mode, onSelect = viewModel::setMode)
            when (state.mode) {
                AiMode.CHAT -> ChatBody(
                    state = state,
                    onInputChange = viewModel::setInput,
                    onSend = { viewModel.send() },
                    onStop = viewModel::stop,
                    onRetry = viewModel::retry,
                    onConfirm = viewModel::confirm,
                    onDismiss = viewModel::dismissCandidate,
                    onUndo = viewModel::undoCandidate,
                    onOpenBills = onOpenBills,
                    onOpenSettings = onOpenAiSettings,
                )

                AiMode.MANUAL -> ManualBody(state = state, onSubmit = viewModel::addManualBill)
            }
        }
    }
}

@Composable
private fun AiHeader(
    state: AiChatUiState,
    onOpenSettings: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI 记账",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when {
                        !state.enabled -> "AI 已关闭 · 手动记账可用"
                        !state.hasKey -> "未配置密钥 · 先去 AI 设置"
                        state.usingBackup -> "备用模型 ${state.modelLabel} · 主模型暂不可用"
                        else -> "当前模型 ${state.modelLabel}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Delete, contentDescription = "清空会话")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "AI 设置")
            }
        }
    }
}

@Composable
private fun ModeSwitch(mode: AiMode, onSelect: (AiMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AiMode.entries.forEach { candidate ->
            if (candidate == mode) {
                Button(onClick = { onSelect(candidate) }, modifier = Modifier.weight(1f)) {
                    Text(candidate.label)
                }
            } else {
                OutlinedButton(onClick = { onSelect(candidate) }, modifier = Modifier.weight(1f)) {
                    Text(candidate.label)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ChatBody(
    state: AiChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onConfirm: (BillCandidate) -> Unit,
    onDismiss: (BillCandidate) -> Unit,
    onUndo: (BillCandidate) -> Unit,
    onOpenBills: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    if (!state.hasKey) {
        SetupHint(onOpenSettings = onOpenSettings)
    }

    Box(modifier = Modifier.weight(1f)) {
        if (state.showEmptyHint) {
            EmptyChatHint(enabled = state.enabled && state.hasKey)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.messages, key = { it.localId }) { message ->
                    MessageBubble(
                        message = message,
                        onConfirm = onConfirm,
                        onDismiss = onDismiss,
                        onUndo = onUndo,
                        onOpenBills = onOpenBills,
                    )
                }
            }
        }
    }

    QuickActions(
        enabled = state.enabled && state.hasKey && !state.sending,
        onPick = { text ->
            onInputChange(text)
        },
    )

    Composer(
        state = state,
        onInputChange = onInputChange,
        onSend = onSend,
        onStop = onStop,
        onRetry = onRetry,
    )
}

@Composable
private fun SetupHint(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("还没有接上 AI", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "填好 Base URL、模型名和 API Key 之后，就能用一句话记账。密钥只加密存在本机，不会上传。",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onOpenSettings) { Text("去 AI 设置") }
        }
    }
}

@Composable
private fun EmptyChatHint(enabled: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("👋", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (enabled) "说一句就能记账，比如「午饭 28 微信」" else "AI 现在不可用，可以切到「手动记账」继续",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "会自动识别：金额 · 收支类型 · 分类 · 时间 · 支付方式 · 标签",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActions(enabled: Boolean, onPick: (String) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val actions = listOf(
            AiChatViewModel.QUICK_EXPENSE,
            AiChatViewModel.QUICK_TODAY,
            AiChatViewModel.QUICK_MONTH,
            AiChatViewModel.QUICK_ANALYZE,
        )
        actions.forEach { text ->
            AssistChip(
                onClick = { onPick(text) },
                enabled = enabled,
                label = { Text(text, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun Composer(
    state: AiChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    Column {
        // 工具调用可能连查几张表，给一句「正在查账单…」，避免用户以为卡住了。
        state.toolHint?.let { hint ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("说一句话，例如：买咖啡 18 支付宝") },
            maxLines = 3,
        )
        if (state.sending) {
            FilledTonalButton(onClick = onStop) {
                Icon(Icons.Default.Clear, contentDescription = "停止生成")
            }
        } else {
            Button(onClick = onSend, enabled = state.canSend) {
                Icon(Icons.Default.Send, contentDescription = "发送")
            }
        }
    }

    if (state.lastError != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.lastError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("重试")
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MessageBubble(
    message: AiChatMessage,
    onConfirm: (BillCandidate) -> Unit,
    onDismiss: (BillCandidate) -> Unit,
    onUndo: (BillCandidate) -> Unit,
    onOpenBills: () -> Unit,
) {
    val isUser = message.role == ChatRole.USER
    if (isUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.failed) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        message.candidates.forEach { candidate ->
            CandidateCard(
                candidate = candidate,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
                onUndo = onUndo,
                onOpenBills = onOpenBills,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateCard(
    candidate: BillCandidate,
    onConfirm: (BillCandidate) -> Unit,
    onDismiss: (BillCandidate) -> Unit,
    onUndo: (BillCandidate) -> Unit,
    onOpenBills: () -> Unit,
) {
    val dismissed = candidate.reasons.any { it == "已忽略" }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.type.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // 外币候选：主数字显示原币，折算成人民币另行标注，避免把 $19.99 当成 ¥19.99。
                    text = Money.format(candidate.amountMinor, candidate.currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColorFor(candidate.type),
                )
            }
            if (candidate.currency != Money.BASE_CURRENCY) {
                Text(
                    text = "折算 ${Money.format(cnyMinorOf(candidate))} · " +
                        "1 ${candidate.currency} = ¥" +
                        Money.formatRate(candidate.exchangeRateScaled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = buildString {
                    append(candidate.categoryName ?: "未分类")
                    append(" · ")
                    append(candidate.paymentMethod.label)
                    append(" · ")
                    append(Time.formatFullDate(candidate.occurredAt))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (candidate.note.isNotBlank()) {
                Text(candidate.note, style = MaterialTheme.typography.bodySmall)
            }
            if (candidate.tagNames.isNotEmpty()) {
                Text(
                    text = candidate.tagNames.joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "置信度 ${(candidate.confidence * 100).toInt()}%" +
                    if (candidate.reasons.isEmpty()) "" else " · 需要确认",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (candidate.reasons.isNotEmpty()) {
                Text(
                    text = candidate.reasons.joinToString("\n") { "· $it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when {
                candidate.applied -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已入账",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = onOpenBills) { Text("查看账单") }
                    TextButton(onClick = { onUndo(candidate) }) { Text("撤销") }
                }

                dismissed -> Text(
                    text = "已忽略这笔",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onConfirm(candidate) }) { Text("确认入账") }
                    TextButton(onClick = { onDismiss(candidate) }) { Text("忽略") }
                }
            }
        }
    }
}

@Composable
private fun ManualBody(state: AiChatUiState, onSubmit: (xyz.ikuyo.finance.domain.model.BillDraft) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            BillEntryForm(
                categories = state.categories,
                tags = state.tags,
                confirmText = "记一笔",
                onConfirm = onSubmit,
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("手动记账完全在本机完成", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "不联网、不经过 AI，写入后同样能在「操作历史」里撤销。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "金额以「分」为整数单位存储，不用浮点数，避免累加误差。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
/** 候选金额按候选自带的汇率快照折算成人民币分，仅供展示。 */
private fun cnyMinorOf(candidate: BillCandidate): Long =
    Money.convertToCnyMinor(
        amountMinor = candidate.amountMinor,
        currency = candidate.currency,
        rate = Money.rateFromScaled(candidate.exchangeRateScaled),
    )
