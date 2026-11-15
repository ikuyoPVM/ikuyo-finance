package xyz.ikuyo.finance.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.AppLanguage
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.notify.DailyReminder

data class MineUiState(
    val themeMode: String = SettingsRepository.THEME_SYSTEM,
    val dynamicColor: Boolean = true,
    val languageMode: String = AppLanguage.SYSTEM,
    val trashCount: Int = 0,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = SettingsRepository.DEFAULT_REMINDER_HOUR,
    val reminderMinute: Int = SettingsRepository.DEFAULT_REMINDER_MINUTE,
)

class MineViewModel : ViewModel() {

    private val settingsRepository = Graph.settingsRepository
    private val billRepository = Graph.billRepository

    /** 语言放在 SharedPreferences（见 AppLanguage），这里只把它映射成界面状态。 */
    private val languageMode = MutableStateFlow(AppLanguage.stored(Graph.appContext))

    /** 提醒开关与时间来自两处偏好，先合成一个对象，避免 combine 参数超限。 */
    private val reminder = combine(
        settingsRepository.dailyReminderEnabled,
        settingsRepository.dailyReminderTime,
    ) { enabled, time -> enabled to time }

    val state: StateFlow<MineUiState> = combine(
        settingsRepository.themeMode,
        settingsRepository.dynamicColor,
        billRepository.observeTrash(),
        languageMode,
        reminder,
    ) { themeMode, dynamicColor, trash, language, reminder ->
        MineUiState(
            themeMode = themeMode,
            dynamicColor = dynamicColor,
            languageMode = language,
            trashCount = trash.size,
            reminderEnabled = reminder.first,
            reminderHour = reminder.second.first,
            reminderMinute = reminder.second.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MineUiState())

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    /**
     * 保存语言后需要重建 Activity 才会生效（资源是按 Context 解析的）。
     * 重建由界面负责，这里只落盘并更新状态。
     */
    fun setLanguage(mode: String) {
        AppLanguage.save(Graph.appContext, mode)
        languageMode.value = mode
    }

    /**
     * 开关每日提醒。
     *
     * 权限申请交给界面（需要 Activity 的 launcher），这里只负责落盘与系统闹钟的排期/取消，
     * 保证「开关状态」和「真的会响」永远一致。
     */
    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDailyReminderEnabled(enabled)
            val time = settingsRepository.dailyReminderTime.first()
            if (enabled) {
                DailyReminder.schedule(Graph.appContext, time.first, time.second)
            } else {
                DailyReminder.cancel(Graph.appContext)
            }
        }
    }

    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setDailyReminderTime(hour, minute)
            // 只有开着的提醒才需要重排；关着的时候改时间不该把闹钟叫醒。
            if (settingsRepository.dailyReminderEnabled.first()) {
                DailyReminder.schedule(Graph.appContext, hour, minute)
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MineViewModel() }
        }
    }
}