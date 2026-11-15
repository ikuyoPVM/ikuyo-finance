package xyz.ikuyo.finance.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.data.repository.SettingsRepository
import xyz.ikuyo.finance.di.Graph

data class MineUiState(
    val themeMode: String = SettingsRepository.THEME_SYSTEM,
    val trashCount: Int = 0,
)

class MineViewModel : ViewModel() {

    private val settingsRepository = Graph.settingsRepository
    private val billRepository = Graph.billRepository

    val state: StateFlow<MineUiState> = kotlinx.coroutines.flow.combine(
        settingsRepository.themeMode,
        billRepository.observeTrash(),
    ) { themeMode, trash ->
        MineUiState(themeMode = themeMode, trashCount = trash.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MineUiState())

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MineViewModel() }
        }
    }
}