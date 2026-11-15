package xyz.ikuyo.finance.feature.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.data.repository.OperationRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.BillOperation
import xyz.ikuyo.finance.domain.usecase.UndoUseCase

data class OperationsUiState(
    val isLoading: Boolean = true,
    val operations: List<BillOperation> = emptyList(),
) {
    val isEmpty: Boolean get() = !isLoading && operations.isEmpty()
}

/**
 * 操作历史（阶段 2 模块六）。
 *
 * 只读展示 + 逐条撤销；历史条目本身永远不参与余额、预算或统计计算。
 */
class OperationsViewModel(
    operationRepository: OperationRepository,
    private val undoUseCase: UndoUseCase,
) : ViewModel() {

    private val channel = Channel<UiEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    val state = operationRepository.observeRecent(100)
        .map { operations -> OperationsUiState(isLoading = false, operations = operations) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OperationsUiState())

    fun undo(operation: BillOperation) {
        viewModelScope.launch {
            undoUseCase(operation.id)
                .onSuccess { emit(it) }
                .onFailure { emit(it.message ?: "撤销失败") }
        }
    }

    private suspend fun emit(text: String) {
        channel.send(UiEvent.Message(text))
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                OperationsViewModel(
                    operationRepository = Graph.operationRepository,
                    undoUseCase = Graph.undoUseCase,
                )
            }
        }
    }
}