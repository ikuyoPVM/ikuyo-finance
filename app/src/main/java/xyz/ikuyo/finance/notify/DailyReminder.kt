package xyz.ikuyo.finance.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import xyz.ikuyo.finance.MainActivity
import xyz.ikuyo.finance.R
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.di.Graph

/**
 * 每日记账提醒。
 *
 * 设计取舍：
 * - 用 [AlarmManager.setAndAllowWhileIdle] 而不是精确闹钟，避免在 Android 12+ 还要申请
 *   `SCHEDULE_EXACT_ALARM`（记账提醒晚几分钟没有影响，省一次敏感权限）。
 * - 触发时先看当天有没有有效账单，有就安静跳过，绝不因为提醒骚扰已经记过账的人。
 * - 每次发完立刻排下一天，所以不需要周期任务，也不依赖 WorkManager。
 */
object DailyReminder {

    const val CHANNEL_ID = "daily_reminder"
    private const val NOTIFICATION_ID = 1001
    private const val REQUEST_CODE = 2001

    /** 创建通知渠道（Android 8.0+ 必须，重复调用无副作用）。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notify_channel_daily),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notify_channel_daily_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** 计算下一次触发时间：今天的时间点已过就顺延到明天。 */
    fun nextTrigger(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val today = Time.toLocalDate(now)
        val candidate = Time.toMillis(today, java.time.LocalTime.of(hour, minute))
        return if (candidate > now) candidate else {
            val tomorrow = today.plusDays(1)
            Time.toMillis(tomorrow, java.time.LocalTime.of(hour, minute))
        }
    }

    fun schedule(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTrigger(hour, minute)
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarmManager.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyReminderReceiver::class.java).apply {
            action = ACTION_FIRE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 通知权限是否已经授予（Android 13+ 需要运行时权限）。 */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun notify(context: Context) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notify_daily_title))
            .setContentText(context.getString(R.string.notify_daily_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notify_daily_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    const val ACTION_FIRE = "xyz.ikuyo.finance.action.DAILY_REMINDER"
}

/**
 * 提醒的广播接收者。
 *
 * 用 goAsync + 后台线程读库：广播里不能做长耗时工作，但读一次账单是毫秒级的。
 */
class DailyReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        try {
            val settings = runBlocking(Dispatchers.IO) {
                Graph.init(appContext)
                val enabled = Graph.settingsRepository.dailyReminderEnabled.first()
                val time = Graph.settingsRepository.dailyReminderTime.first()
                if (!enabled) return@runBlocking null
                val hasBillToday = hasBillToday(appContext)
                if (!hasBillToday) DailyReminder.notify(appContext)
                // 无论今天是否已记账，都把下一天排上，保证链条不断。
                DailyReminder.schedule(appContext, time.first, time.second)
                time
            }
        } finally {
            pending.finish()
        }
    }

    private suspend fun hasBillToday(context: Context): Boolean {
        val now = Time.nowMillis()
        val start = Time.startOfDay(Time.toLocalDate(now))
        val end = Time.endOfDayExclusive(Time.toLocalDate(now))
        return Graph.billRepository.observeActive().first().any { it.occurredAt in start until end }
    }
}