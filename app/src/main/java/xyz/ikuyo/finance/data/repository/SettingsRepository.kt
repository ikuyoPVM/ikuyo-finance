package xyz.ikuyo.finance.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

    companion object {
        private val KEY_SORT = stringPreferencesKey("bill_sort_order")
        private val KEY_PAYMENT = stringPreferencesKey("last_payment_method")
        private val KEY_THEME = stringPreferencesKey("theme_mode")

        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"
    }
}