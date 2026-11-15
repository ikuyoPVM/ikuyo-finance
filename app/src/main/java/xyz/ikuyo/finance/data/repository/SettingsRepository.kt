package xyz.ikuyo.finance.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.domain.model.BillSortOrder
import xyz.ikuyo.finance.domain.model.PaymentMethod

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("ikuyo_settings")

/** 轻量偏好设置（DataStore）。敏感配置（API Key）后续由 Keystore 单独保存。 */
class SettingsRepository(private val context: Context) {

    val sortOrder: Flow<BillSortOrder> = context.settingsDataStore.data.map { prefs ->
        BillSortOrder.fromDb(prefs[KEY_SORT] ?: BillSortOrder.OCCURRED_DESC.dbValue)
    }

    val lastPaymentMethod: Flow<PaymentMethod?> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_PAYMENT]?.let { PaymentMethod.fromDb(it) }
    }

    val themeMode: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_THEME] ?: THEME_SYSTEM
    }

    suspend fun setSortOrder(order: BillSortOrder) {
        context.settingsDataStore.edit { it[KEY_SORT] = order.dbValue }
    }

    suspend fun setLastPaymentMethod(method: PaymentMethod) {
        context.settingsDataStore.edit { it[KEY_PAYMENT] = method.dbValue }
    }

    suspend fun setThemeMode(mode: String) {
        context.settingsDataStore.edit { it[KEY_THEME] = mode }
    }

    /** 是否启用 Material You 动态取色（仅 Android 12+ 有实际效果）。 */
    val dynamicColor: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_DYNAMIC_COLOR] ?: true
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    /**
     * 固定支出提前提醒的天数。
     *
     * 0 表示只有到期当天才提醒；默认 3 天，够覆盖「周末前把房租交了」这类习惯。
     */
    val fixedReminderDays: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[KEY_FIXED_REMIND_DAYS] ?: DEFAULT_FIXED_REMIND_DAYS).coerceIn(0, 30)
    }

    suspend fun setFixedReminderDays(days: Int) {
        context.settingsDataStore.edit { it[KEY_FIXED_REMIND_DAYS] = days.coerceIn(0, 30) }
    }

    /**
     * 每日记账提醒开关。
     *
     * 只在「当天还没有任何有效账单」时才发通知，避免打扰已经记过账的人。
     */
    val dailyReminderEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_REMINDER_ENABLED] ?: false
    }

    /** 每日提醒时间（24 小时制，默认 21:00，晚间提醒当天补记）。 */
    val dailyReminderTime: Flow<Pair<Int, Int>> = context.settingsDataStore.data.map { prefs ->
        val hour = (prefs[KEY_REMINDER_HOUR] ?: DEFAULT_REMINDER_HOUR).coerceIn(0, 23)
        val minute = (prefs[KEY_REMINDER_MINUTE] ?: DEFAULT_REMINDER_MINUTE).coerceIn(0, 59)
        hour to minute
    }

    suspend fun setDailyReminderEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_REMINDER_ENABLED] = enabled }
    }

    suspend fun setDailyReminderTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[KEY_REMINDER_HOUR] = hour.coerceIn(0, 23)
            it[KEY_REMINDER_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    companion object {
        private val KEY_SORT = stringPreferencesKey("bill_sort_order")
        private val KEY_PAYMENT = stringPreferencesKey("last_payment_method")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_FIXED_REMIND_DAYS = intPreferencesKey("fixed_reminder_days")
        private val KEY_REMINDER_ENABLED = booleanPreferencesKey("daily_reminder_enabled")
        private val KEY_REMINDER_HOUR = intPreferencesKey("daily_reminder_hour")
        private val KEY_REMINDER_MINUTE = intPreferencesKey("daily_reminder_minute")

        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"

        /** 固定支出默认提前 3 天提醒。 */
        const val DEFAULT_FIXED_REMIND_DAYS = 3

        /** 每日提醒默认 21:00，晚间提醒当天补记。 */
        const val DEFAULT_REMINDER_HOUR = 21
        const val DEFAULT_REMINDER_MINUTE = 0
    }
}