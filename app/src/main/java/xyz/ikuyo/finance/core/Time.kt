package xyz.ikuyo.finance.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间工具。所有时间以 epoch millis 保存，展示时按系统时区换算。
 * 记录账单时必须同时保存「消费发生时间」与「录入时间」。
 */
object Time {

    val zone: ZoneId get() = ZoneId.systemDefault()

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd", Locale.getDefault())
    private val fullDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    private val monthFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    fun nowMillis(): Long = System.currentTimeMillis()

    fun toLocalDateTime(millis: Long): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    fun toLocalDate(millis: Long): LocalDate = toLocalDateTime(millis).toLocalDate()

    fun toMillis(date: LocalDate, time: LocalTime = LocalTime.NOON): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDayExclusive(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun startOfMonth(month: YearMonth = YearMonth.now(zone)): Long =
        startOfDay(month.atDay(1))

    fun endOfMonthExclusive(month: YearMonth = YearMonth.now(zone)): Long =
        startOfDay(month.plusMonths(1).atDay(1))

    fun startOfWeek(date: LocalDate = LocalDate.now(zone)): Long =
        startOfDay(date.with(DayOfWeek.MONDAY))

    fun endOfWeekExclusive(date: LocalDate = LocalDate.now(zone)): Long =
        startOfDay(date.with(DayOfWeek.MONDAY).plusDays(7))

    fun monthOf(millis: Long): YearMonth = YearMonth.from(toLocalDate(millis))

    fun formatDateTime(millis: Long): String = dateTimeFormatter.format(toLocalDateTime(millis))

    fun formatDate(millis: Long): String = dateFormatter.format(toLocalDate(millis))

    fun formatFullDate(millis: Long): String = fullDateFormatter.format(toLocalDate(millis))

    fun formatMonth(month: YearMonth): String = monthFormatter.format(month)

    fun formatTime(millis: Long): String = timeFormatter.format(toLocalDateTime(millis))

    /** 今天 / 昨天 / M月d日 */
    fun describeDay(millis: Long, today: LocalDate = LocalDate.now(zone)): String {
        val date = toLocalDate(millis)
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> fullDateFormatter.format(date)
        }
    }

    /** 周期剩余天数（含今天）；已结束返回 0。 */
    fun daysLeft(periodEndInclusive: Long, today: LocalDate = LocalDate.now(zone)): Int {
        val end = toLocalDate(periodEndInclusive)
        if (end.isBefore(today)) return 0
        return (end.toEpochDay() - today.toEpochDay() + 1).toInt()
    }

    fun endOfMonthInclusive(month: YearMonth = YearMonth.now(zone)): Long =
        endOfMonthExclusive(month) - 1

    fun previousMonth(month: YearMonth = YearMonth.now(zone)): YearMonth = month.minusMonths(1)
}