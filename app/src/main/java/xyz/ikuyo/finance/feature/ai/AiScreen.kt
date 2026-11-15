package xyz.ikuyo.finance.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.ikuyo.finance.ui.components.DividerThin
import xyz.ikuyo.finance.ui.components.SectionCard
import xyz.ikuyo.finance.ui.components.SectionTitle

/**
 * AI 记账入口。
 *
 * 阶段 3 才接入 OpenAI 兼容接口；当前版本先给出能力说明与手动记账入口，
 * 保证底部导航的第三个 Tab 可用，且不出现任何「AI 直接改余额」的假象。
 */
@Composable
fun AiScreen(
    onBackToHome: () -> Unit = {},
    onOpenBills: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "AI 记账",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "自然语言一句话记账，正在开发中（阶段 3）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard {
            SectionTitle(title = "计划中的能力")
            Spacer(modifier = Modifier.height(8.dp))
            listOf(
                "「午饭 28 微信」→ 自动识别金额、分类、支付方式",
                "多币种与旅行消费，按录入时汇率折算人民币",
                "截图 / 小票识别，结果先给草稿，确认后才写库",
                "只生成草稿，永远不直接修改余额与预算",
            ).forEach { line ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("·", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }

        SectionCard {
            SectionTitle(title = "现在可以做什么")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "AI 未接入前，所有账单都可以在首页手动记录，数据同样完整进入统计与预算。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBackToHome, modifier = Modifier.weight(1f)) {
                    Text("去首页记账")
                }
                OutlinedButton(onClick = onOpenBills, modifier = Modifier.weight(1f)) {
                    Text("查看账单")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            DividerThin()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "接口密钥将使用 Android Keystore 加密保存，不写入日志、不进入版本库。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}