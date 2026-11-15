package xyz.ikuyo.finance.feature.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Category

data class CategoriesUiState(
    val isLoading: Boolean = true,
    val categories: List<Category> = emptyList(),
) {
    val defaults: List<Category> get() = categories.filter { it.isDefault }
    val custom: List<Category> get() = categories.filterNot { it.isDefault }
}

class CategoriesViewModel : ViewModel() {

    private val repository = Graph.categoryRepository

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val state: StateFlow<CategoriesUiState> = repository.observeAll()
        .map { list -> CategoriesUiState(isLoading = false, categories = list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun add(name: String, emoji: String, colorArgb: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            emit("分类名称不能为空")
            return
        }
        viewModelScope.launch {
            repository.add(trimmed, emoji.ifBlank { "📦" }, colorArgb)
                .onSuccess { emit("已添加分类：$trimmed") }
                .onFailure { emit(it.message ?: "添加失败") }
        }
    }

    fun rename(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            emit("分类名称不能为空")
            return
        }
        viewModelScope.launch {
            repository.rename(id, trimmed)
                .onSuccess { emit("已重命名为：$trimmed") }
                .onFailure { emit(it.message ?: "重命名失败") }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.deleteCustom(id)
                .onSuccess { emit("已删除分类") }
                .onFailure { emit(it.message ?: "删除失败") }
        }
    }

    private fun emit(text: String) {
        viewModelScope.launch { _events.emit(UiEvent.Message(text)) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { CategoriesViewModel() }
        }
    }
}