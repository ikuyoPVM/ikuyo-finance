package xyz.ikuyo.finance.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import xyz.ikuyo.finance.domain.model.BudgetAlertLevel
import xyz.ikuyo.finance.domain.model.BudgetScope

private val Context.budgetAlertDataStore: DataStore<Preferences> by
    preferencesDataStore("ikuyo_budget_alerts")

/**
 * 预算预警去重（计划书 8.5）。
 *
 * 去重键 = 预算对象 + 周期起点 + 预算金额 + 预警级别：
 * - 同一周期同一级别只提醒一次（进下一级会再提醒）；
 * - 预算金额调整后键变化，会重新计算并提醒（对应「预算调整后重新计算状态」）；
 * - 新周期开始时周期起点变化，键随之变化，预警自动重置。
 */
class BudgetAlertRepository(private val context: Context) {

    suspend fun isAcknowledged(key: String): Boolean {
        val current = context.budgetAlertDataStore.data.first()[KEY_ACKNOWLEDGED]
        return current?.contains(key) == true
    }

    suspend fun acknowledge(key: String) {
        context.budgetAlertDataStore.edit { prefs ->
            val current = prefs[KEY_ACKNOWLEDGED].orEmpty().toMutableSet()
            current.add(key)
            // 超过上限时整体重置，避免 DataStore 无限膨胀（极低频场景）。
            prefs[KEY_ACKNOWLEDGED] = if (current.size > MAX_ENTRIES) setOf(key) else current
        }
    }

    companion object {
        private val KEY_ACKNOWLEDGED = stringSetPreferencesKey("acknowledged_alerts")
        private const val MAX_ENTRIES = 500

        fun keyOf(
            scope: BudgetScope,
            categoryId: Long?,
            windowStart: Long,
            amountMinor: Long,
            level: BudgetAlertLevel,
        ): String = "${scope.dbValue}:${categoryId ?: "-"}:$windowStart:$amountMinor:${level.name}"
    }
}