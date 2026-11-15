package xyz.ikuyo.finance.data.backup

import xyz.ikuyo.finance.data.repository.CurrencyRateRepository
import xyz.ikuyo.finance.data.repository.HomeLayoutRepository
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.domain.model.BillSortOrder
import xyz.ikuyo.finance.domain.model.CurrencyRate
import xyz.ikuyo.finance.domain.model.HomeLayout
import xyz.ikuyo.finance.domain.model.HomeModule
import xyz.ikuyo.finance.domain.model.PaymentMethod
import xyz.ikuyo.finance.domain.model.RateSource
import kotlinx.coroutines.flow.first

/**
 * 账本设置的备份读写口子。
 *
 * 设置分散在几个 DataStore 里，备份层不去碰它们的存储格式，
 * 统一从这里进出，恢复时也只走各仓储的公开方法。
 */
interface BackupSettingsPort {
    suspend fun export(): BackupSettings
    suspend fun restore(values: Map<String, String>)
}

/** 键名稳定，供备份文件长期使用（改名要配迁移）。 */
object BackupSettingKeys {
    const val SORT_ORDER = "sortOrder"
    const val THEME_MODE = "themeMode"
    const val FIXED_REMINDER_DAYS = "fixedReminderDays"
    const val LAST_PAYMENT_METHOD = "lastPaymentMethod"
    const val HOME_ORDER = "homeLayout.order"
    const val HOME_HIDDEN = "homeLayout.hidden"
    const val HOME_COLLAPSED = "homeLayout.collapsed"
    const val CURRENCY_RATES = "currencyRates"
}

class RepoSettingsPort(
    private val settings: SettingsRepository,
    private val homeLayout: HomeLayoutRepository,
    private val rates: CurrencyRateRepository,
) : BackupSettingsPort {

    override suspend fun export(): BackupSettings {
        val values = linkedMapOf<String, String>()
        values[BackupSettingKeys.SORT_ORDER] = settings.sortOrder.first().dbValue
        values[BackupSettingKeys.THEME_MODE] = settings.themeMode.first()
        values[BackupSettingKeys.FIXED_REMINDER_DAYS] = settings.fixedReminderDays.first().toString()
        settings.lastPaymentMethod.first()?.let {
            values[BackupSettingKeys.LAST_PAYMENT_METHOD] = it.dbValue
        }
        val layout = homeLayout.layout.first()
        values[BackupSettingKeys.HOME_ORDER] = layout.order.joinToString(",") { it.key }
        values[BackupSettingKeys.HOME_HIDDEN] = layout.hidden.joinToString(",") { it.key }
        values[BackupSettingKeys.HOME_COLLAPSED] = layout.collapsed.joinToString(",") { it.key }
        values[BackupSettingKeys.CURRENCY_RATES] = rates.snapshot()
            .values
            .filterNot { it.isBase }
            .sortedBy { it.currency }
            .joinToString(";") { "${it.currency}:${it.rateScaled}:${it.source.dbValue}:${it.updatedAt}" }
        return BackupSettings(values)
    }

    override suspend fun restore(values: Map<String, String>) {
        values[BackupSettingKeys.SORT_ORDER]?.let { raw ->
            BillSortOrder.fromDb(raw).let { settings.setSortOrder(it) }
        }
        values[BackupSettingKeys.THEME_MODE]?.takeIf { it.isNotBlank() }?.let {
            settings.setThemeMode(it)
        }
        values[BackupSettingKeys.FIXED_REMINDER_DAYS]?.toIntOrNull()?.let {
            settings.setFixedReminderDays(it)
        }
        values[BackupSettingKeys.LAST_PAYMENT_METHOD]?.let { raw ->
            PaymentMethod.fromDb(raw).let { settings.setLastPaymentMethod(it) }
        }
        val order = values[BackupSettingKeys.HOME_ORDER].toModules()
        if (order.isNotEmpty()) {
            homeLayout.update(
                HomeLayout(
                    order = HomeModule.normalize(order.map { it.key }),
                    hidden = values[BackupSettingKeys.HOME_HIDDEN].toModules().toSet(),
                    collapsed = values[BackupSettingKeys.HOME_COLLAPSED].toModules().toSet(),
                )
            )
        }
        values[BackupSettingKeys.CURRENCY_RATES]?.split(';')?.forEach { entry ->
            val parts = entry.split(':')
            if (parts.size < 4) return@forEach
            val currency = parts[0]
            val rateScaled = parts[1].toLongOrNull() ?: return@forEach
            val source = RateSource.entries.firstOrNull { it.dbValue == parts[2] } ?: RateSource.MANUAL
            val updatedAt = parts[3].toLongOrNull() ?: 0L
            rates.setRate(currency, rateScaled, source, updatedAt)
        }
    }

    private fun String?.toModules(): List<HomeModule> =
        this?.split(',')?.mapNotNull { HomeModule.fromKey(it.trim()) } ?: emptyList()

    /** 只在备份里用，避免把内部编码散到别处。 */
    private fun CurrencyRate.encode(): String = "$currency:$rateScaled:${source.dbValue}:$updatedAt"
}