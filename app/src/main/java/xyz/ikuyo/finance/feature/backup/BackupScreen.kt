package xyz.ikuyo.finance.feature.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.data.backup.BackupFormat
import xyz.ikuyo.finance.ui.components.EmptyHint
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit = {},
    viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var usePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var showPasswordPanel by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.MIME_BACKUP)
    ) { uri: Uri? ->
        val text = state.exportText
        if (uri == null || text == null) {
            viewModel.consumeExport()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选位置")
                }.isSuccess
            }
            viewModel.consumeExport()
            if (!ok) viewModel.reportError("写入备份文件失败，请换一个保存位置")
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                viewModel.reportError("无法读取所选文件")
            } else {
                viewModel.loadFile(text)
            }
        }
    }

    // 备份生成后立刻拉起系统保存面板，避免文本长时间留在内存里。
    LaunchedEffect(state.exportText) {
        if (state.exportText != null) {
            saveLauncher.launch(defaultFileName())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据备份与恢复") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            state.notice?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            SectionTitle(title = "导出备份")
            SectionCard {
                Text(
                    text = "备份包含全部账单、分类、标签、预算、固定支出、分期、债务、储蓄与汇率快照，" +
                        "不包含 AI API Key。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = usePassword, onCheckedChange = { usePassword = it })
                    Spacer(Modifier.width(8.dp))
                    Text("用密码加密备份", style = MaterialTheme.typography.bodyMedium)
                }
                if (usePassword) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("备份密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passwordConfirm,
                        onValueChange = { passwordConfirm = it },
                        label = { Text("再输入一次") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (passwordConfirm.isNotEmpty() && password != passwordConfirm) {
                        Text(
                            text = "两次输入的密码不一致",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (usePassword) {
                            when {
                                password.isBlank() -> viewModel.reportError("请输入备份密码")
                                password != passwordConfirm -> viewModel.reportError("两次输入的密码不一致")
                                else -> viewModel.exportBackup(password)
                            }
                        } else {
                            viewModel.exportBackup(null)
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.busy) "处理中…" else "生成备份文件")
                }
                Text(
                    text = "加密使用 PBKDF2 派生密钥 + AES-GCM，密码不会被保存；忘记密码将无法恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle(title = "从备份恢复")
            SectionCard {
                Text(
                    text = "恢复会覆盖当前账本数据。执行前会先校验文件、展示预览，" +
                        "确认后在一个事务里写入，失败时不会留下半套数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { openLauncher.launch(arrayOf("*/*")) },
                        enabled = !state.busy,
                    ) { Text("选择备份文件") }
                    if (state.restoreStage != RestoreStage.NONE) {
                        OutlinedButton(onClick = viewModel::cancelRestore, enabled = !state.busy) {
                            Text("取消")
                        }
                    }
                }

                if (state.restoreStage == RestoreStage.PASSWORD) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("这个备份已加密，请输入备份密码", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("备份密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.error?.let { message ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.submitPassword(password) },
                        enabled = !state.busy && password.isNotBlank(),
                    ) { Text("解密并校验") }
                }

                state.preview?.let { preview ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    SectionTitle(title = "恢复预览")
                    PreviewRow("账单", "${preview.billCount} 笔")
                    PreviewRow("分类", "${preview.categoryCount} 个")
                    PreviewRow("支出合计", Money.format(preview.expenseMinor))
                    PreviewRow("收入合计", Money.format(preview.incomeMinor))
                    PreviewRow("退款合计", Money.format(preview.refundMinor))
                    PreviewRow("备份创建时间", Time.formatDateTime(preview.createdAt))
                    PreviewRow("备份来自版本", "${preview.appVersion} · 数据版本 v${preview.dataVersion}")
                    PreviewRow("加密", if (preview.encrypted) "已加密" else "未加密")

                    val issues = state.issues
                    if (issues != null && !issues.isBlocking && issues.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "提示：\n" + issues.warnings.joinToString("\n") { "· $it" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (issues != null && issues.isBlocking) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "无法恢复：\n" + issues.errors.joinToString("\n") { "· $it" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::confirmRestore,
                        enabled = !state.busy && (issues?.isBlocking != true),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.busy) "恢复中…" else "确认覆盖当前账本")
                    }
                }

                state.report?.let { report ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    SectionTitle(title = "恢复完成")
                    PreviewRow("账单", "${report.billCount} 笔")
                    PreviewRow("分类", "${report.categoryCount} 个")
                    PreviewRow("支出合计", Money.format(report.expenseMinor))
                    PreviewRow("收入合计", Money.format(report.incomeMinor))
                    PreviewRow("退款合计", Money.format(report.refundMinor))
                    PreviewRow("耗时", "${report.durationMillis} ms")
                    PreviewRow(
                        "设置",
                        if (report.settingsRestored) "已恢复" else (report.settingsWarning ?: "未恢复"),
                    )
                    if (report.warnings.isNotEmpty()) {
                        Text(
                            text = report.warnings.joinToString("\n") { "· $it" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.restoreStage == RestoreStage.NONE && state.preview == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "尚未选择文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionTitle(title = "数据安全")
            SectionCard {
                Text(
                    text = "完整性检查会把当前账本重新导出一遍并校验结构与关联关系。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::checkIntegrity, enabled = !state.busy) {
                    Text("检查本地数据")
                }
                state.integrityMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.integrityClean == false) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "清空数据会删除全部账单、分类、标签、预算、固定支出、分期、债务与储蓄记录，" +
                        "只重建 12 个默认分类，且无法撤销。建议先导出一份备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showWipeConfirm = true },
                    enabled = !state.busy,
                ) { Text("清空本地数据") }
            }

            Spacer(Modifier.height(24.dp))
            EmptyHint(text = "备份文件后缀为 ${BackupFormat.FILE_SUFFIX}，可以保存到下载目录或云盘。")
        }
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("确认清空本地数据？") },
            text = {
                Text("这一步会删除全部账本记录，只保留默认分类。清空后无法撤销，除非你之前导出过备份。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWipeConfirm = false
                        viewModel.clearAllData()
                    }
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun defaultFileName(): String {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
    return "ikuyo-backup-$stamp${BackupFormat.FILE_SUFFIX}"
}