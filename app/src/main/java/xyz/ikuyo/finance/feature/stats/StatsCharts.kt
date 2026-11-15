package xyz.ikuyo.finance.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.ikuyo.finance.core.Money
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.domain.usecase.Statistics

/**
 * 趋势折线：按 [Statistics.DailyPoint] 画净支出曲线，点按某一天回调上层下钻。
 *
 * 金额口径与明细完全一致：点的高度 = 当天 `netExpenseMinor`（退款已冲减）。
 */
@Composable
fun TrendLineChart(
    points: List<Statistics.DailyPoint>,
    modifier: Modifier = Modifier,
    height: Int = 150,
    onPointClick: (Statistics.DailyPoint) -> Unit = {},
) {
    if (points.isEmpty()) return
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.16f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val maxValue = points.maxOf { it.netExpenseMinor }.coerceAtLeast(1L)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val step = if (points.size <= 1) 0f else size.width.toFloat() / (points.size - 1)
                    val index = if (step <= 0f) 0 else (offset.x / step).toInt().coerceIn(0, points.lastIndex)
                    onPointClick(points[index])
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val stepX = if (points.size <= 1) 0f else w / (points.size - 1)

        // 三条参考线：0%、50%、100%，便于目测比较不同日期的相对高低。
        listOf(0f, 0.5f, 1f).forEach { ratio ->
            val y = h - ratio * h
            drawLine(color = gridColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
        }

        val path = Path()
        val area = Path()
        points.forEachIndexed { index, point ->
            val x = index * stepX
            val ratio = point.netExpenseMinor.toFloat() / maxValue
            val y = h - ratio * (h - 6f) - 3f
            if (index == 0) {
                path.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo((points.size - 1) * stepX, h)
        area.close()
        drawPath(area, color = fillColor)
        drawPath(path, color = lineColor, style = Stroke(width = 3f))

        points.forEachIndexed { index, point ->
            if (point.netExpenseMinor <= 0L) return@forEachIndexed
            val x = index * stepX
            val ratio = point.netExpenseMinor.toFloat() / maxValue
            val y = h - ratio * (h - 6f) - 3f
            drawCircle(color = lineColor, radius = 3.5f, center = Offset(x, y))
        }
    }
}

/** 折线图下方的日期刻度：只画首/中/末三个，避免文字重叠。 */
@Composable
fun TrendAxisLabels(points: List<Statistics.DailyPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) return
    val indexes = listOf(0, points.size / 2, points.lastIndex).distinct()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        indexes.forEach { index ->
            Text(
                text = Time.formatMonthDay(points[index].millis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 分类占比环形图：每段颜色取分类自身色值，中心显示区间净支出。
 *
 * 占比来自 [Statistics.CategoryStat.shareBp]（万分比），保证与排名列表一致。
 */
@Composable
fun CategoryDonut(
    stats: List<Statistics.CategoryStat>,
    totalNetExpenseMinor: Long,
    modifier: Modifier = Modifier,
    size: Int = 148,
) {
    val background = MaterialTheme.colorScheme.surfaceVariant
    val fallbackColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
    )
    Box(
        modifier = modifier.size(size.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size.dp)) {
            val stroke = size.dp.toPx() * 0.18f
            val inset = stroke / 2f
            val diameter = size.dp.toPx() - stroke
            drawArc(
                color = background,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = Stroke(width = stroke),
            )
            var startAngle = -90f
            stats.forEachIndexed { index, stat ->
                val sweep = stat.shareBp / 10_000f * 360f
                if (sweep <= 0f) return@forEachIndexed
                val color = if (stat.colorArgb != 0) Color(stat.colorArgb) else fallbackColors[index % fallbackColors.size]
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = Stroke(width = stroke),
                )
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "净支出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = Money.format(totalNetExpenseMinor),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 分类排名：名次 + emoji + 分类名 + 金额 + 相对最长条的横向柱。 */
@Composable
fun CategoryRankRow(
    rank: Int,
    stat: Statistics.CategoryStat,
    maxNetExpenseMinor: Long,
    modifier: Modifier = Modifier,
    changeText: String? = null,
    onClick: () -> Unit = {},
) {
    val barColor = if (stat.colorArgb != 0) Color(stat.colorArgb) else MaterialTheme.colorScheme.primary
    val fraction = if (maxNetExpenseMinor <= 0L) 0f else (stat.netExpenseMinor.toFloat() / maxNetExpenseMinor).coerceIn(0f, 1f)
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(stat.categoryId) { detectTapGestures { onClick() } }
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(18.dp),
            )
            Text(text = stat.emoji, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stat.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (changeText != null) {
                Text(
                    text = changeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = Money.format(stat.netExpenseMinor),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${stat.shareBp / 100}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.End,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 26.dp, top = 4.dp)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(barColor, RoundedCornerShape(3.dp)),
            )
        }
    }
}