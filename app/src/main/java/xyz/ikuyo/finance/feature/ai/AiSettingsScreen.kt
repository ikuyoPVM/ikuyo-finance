package xyz.ikuyo.finance.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.domain.model.AiConfig
import xyz.ikuyo.finance.domain.model.AiPersona
import xyz.ikuyo.finance.ui.components.SectionCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit = {},
    viewModel: AiSettingsViewModel = viewModel(factory = AiSettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> if (event is UiEvent.Message) snackbar.showSnackbar(event.text) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(onBack) }
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("启用 AI", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "关闭后 AI 页面只保留手动记账，所有请求都不会发出。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.config.enabled, onCheckedChange = viewModel::setEnabled)
                    }
                }
            }
            item {
                EndpointCard(
                    title = "主模型",
                    baseUrl = state.config.primary.baseUrl,
                    model = state.config.primary.model,
                    hint = "Base URL 只写到 /v1，程序会自动补 /chat/completions",
                    onSave = viewModel::savePrimary,
                )
            }
            item {
                EndpointCard(
                    title = "备用模型（可选）",
                    baseUrl = state.config.backup?.baseUrl.orEmpty(),
                    model = state.config.backup?.model.orEmpty(),
                    hint = "主模型连续失败时自动切换；留空表示不使用备用",
                    onSave = viewModel::saveBackup,
                    onClear = if (state.config.hasBackup) viewModel::clearBackup else null,
                )
            }
            item { ApiKeyCard(state.key, viewModel::saveApiKey, viewModel::clearApiKey) }
            item { TestCard(state.test, viewModel::testConnection) }
            item { AdvancedCard(state.config, viewModel::setTimeoutSeconds, viewModel::setAutoPostThreshold) }
            item {
                PersonaCard(
                    persona = state.config.persona,
                    onSave = viewModel::setPersona,
                    onReset = viewModel::resetPersona,
                )
            }
            item { PrivacyCard() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
        Column {
            Text("AI 设置", style = MaterialTheme.typography.titleLarge)
            Text(
                "密钥只加密存在本机，不会进入日志、数据库或导出文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EndpointCard(
    title: String,
    baseUrl: String,
    model: String,
    hint: String,
    onSave: (String, String) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var name by remember(model) { mutableStateOf(model) }

    SectionCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("模型名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(url, name) }, enabled = url.isNotBlank() && name.isNotBlank()) {
                Text("保存")
            }
            if (onClear != null) {
                OutlinedButton(onClick = onClear) { Text("清除备用") }
            }
        }
    }
}

@Composable
private fun ApiKeyCard(key: KeyStatus, onSave: (String) -> Unit, onClear: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    SectionCard {
        Text("API Key", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        when {
            key.decryptFailed -> Text(
                "本机保存的密钥无法解密（可能换过设备或系统重置），请重新填写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            key.hasKey -> Text(
                "已保存：${key.masked ?: "••••"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Text(
                "还没有配置密钥，AI 功能不可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(if (key.hasKey) "替换为新的 Key" else "sk-…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(draft.trim())
                    draft = ""
                },
                enabled = draft.trim().length >= 8,
            ) { Text("加密保存") }
            if (key.hasKey) {
                OutlinedButton(onClick = { confirmClear = true }) { Text("删除密钥") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "使用 Android Keystore 的 AES/GCM 加密，密文存在设备保护存储里；界面只显示掩码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("删除本机密钥？") },
            text = { Text("删除后 AI 功能会立刻不可用，账单数据不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TestCard(test: TestState, onTest: () -> Unit) {
    SectionCard {
        Text("连通性测试", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        val detail = when (test) {
            TestState.Idle -> "发一条最小的请求，能区分鉴权失败、模型不存在、额度不足、超时等错误。"
            TestState.Running -> "正在请求…"
            is TestState.Success ->
                "成功：${test.model}${if (test.usedBackup) "（备用）" else ""} · ${test.latencyMillis} ms"
            is TestState.Failure -> "失败：${test.message}"
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = when (test) {
                is TestState.Failure -> MaterialTheme.colorScheme.error
                is TestState.Success -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onTest, enabled = test != TestState.Running) {
            Text(if (test == TestState.Running) "测试中…" else "测试连接")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedCard(config: AiConfig, onTimeout: (Int) -> Unit, onThreshold: (Double) -> Unit) {
    SectionCard {
        Text("高级参数", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text("请求超时", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 45, 90).forEach { seconds ->
                FilterChip(
                    selected = config.timeoutSeconds == seconds,
                    onClick = { onTimeout(seconds) },
                    label = { Text("$seconds 秒") },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "自动入账阈值（分类置信度达到才会跳过确认）",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.7, 0.85, 0.95).forEach { value ->
                FilterChip(
                    selected = kotlin.math.abs(config.autoPostThreshold - value) < 0.001,
                    onClick = { onThreshold(value) },
                    label = { Text("${(value * 100).toInt()}%") },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "当前：超时 ${config.timeoutSeconds} 秒 · max_tokens ${config.maxTokens} · temperature ${config.temperature}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaCard(persona: AiPersona, onSave: (AiPersona) -> Unit, onReset: () -> Unit) {
    var draft by remember(persona) { mutableStateOf(persona) }

    SectionCard {
        Text("人设（只影响表达，不影响任何金额计算）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = { Text("称呼") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.role,
            onValueChange = { draft = draft.copy(role = it) },
            label = { Text("角色（如「记账搭子」）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        ChipRow("语气", AiPersona.TONES, draft.tone) { draft = draft.copy(tone = it) }
        ChipRow("长度", AiPersona.LENGTHS, draft.length) { draft = draft.copy(length = it) }
        ChipRow("表情", AiPersona.EMOJIS, draft.emoji) { draft = draft.copy(emoji = it) }
        ChipRow("建议风格", AiPersona.ADVICE, draft.adviceStyle) { draft = draft.copy(adviceStyle = it) }
        ChipRow("语言", AiPersona.LANGUAGES, draft.language) { draft = draft.copy(language = it) }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.systemPrompt,
            onValueChange = { draft = draft.copy(systemPrompt = it) },
            label = { Text("自定义系统提示词（可选）") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(draft) }) { Text("保存人设") }
            OutlinedButton(onClick = onReset) { Text("恢复默认") }
        }
        if (!draft.isDefault) {
            Spacer(Modifier.height(6.dp))
            Text(
                "预览：${draft.styleHint()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, text) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(text) },
            )
        }
    }
}

@Composable
private fun PrivacyCard() {
    SectionCard {
        Text("会发送哪些数据", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            """
            · 判断分类时：只发交易描述和分类名称列表
            · 查账时：只发匹配到的账单
            · 预算相关：只发必要的额度、余额与趋势
            · 消费分析：只发本期的分类、标签与预算汇总数字，不发备注原文
            · 消费预测：只发必要的近期趋势、剩余天数与预算额度
            · 异常检测：只发近 7 天与个人历史的对比统计，不发完整账本
            · 月度总结：只发汇总数字，不发单条明细
            · 纯闲聊：不发任何账单数据
            这些内容会发送到你自己在「主模型 / 备用模型」里填写的第三方 API；
            模型与接口由你选择，本应用不代管这些请求。不会上传完整账本，也不会把 Key 写进日志或导出文件。
            """.trimIndent(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "AI 只能在确认后通过现有的业务层写入账单，不能修改余额、预算或历史账单。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}