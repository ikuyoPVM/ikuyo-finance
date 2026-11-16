package xyz.ikuyo.finance.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.NoticeKind
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.data.repository.CategoryRepository
import xyz.ikuyo.finance.data.repository.TagRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillDraft
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Tag
import xyz.ikuyo.finance.domain.usecase.EditBillUseCase

data class BillEditUiState(
    val isLoading: Boolean = true,
    val bill: Bill? = null,
    val categories: List<Category> = emptyList(),
    val allTags: List<Tag> = emptyList(),
    val tagIds: List<Long> = emptyList(),
) {
    val isMissing: Boolean get() = !isLoading && bill == null
}

/** 编辑既有账单：保存走 EditBillUseCase，写入操作历史（可撤销）。 */
class BillEditViewModel(
    private val billId: Long,
    billRepository: BillRepository,
    categoryRepository: CategoryRepository,
    tagRepository: TagRepository,
    private val editBillUseCase: EditBillUseCase,
) : ViewModel() {

    private val channel = Channel<UiEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    private val savedFlag = MutableStateFlow(false)
    val saved = savedFlag

    val state = combine(
        billRepository.observeActive(),
        categoryRepository.observeAll(),
        tagRepository.observeAll(),
        tagRepository.observeAllLinks(),
    ) { bills, categories, tags, links ->
        val bill = bills.firstOrNull { it.id == billId }
        BillEditUiState(
            isLoading = false,
            bill = bill,
            categories = categories,
            allTags = tags,
            tagIds = links.filter { it.billId == billId }.map { it.tagId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BillEditUiState())

    fun save(draft: BillDraft) {
        viewModelScope.launch {
            editBillUseCase(billId, draft)
                .onSuccess {
                    savedFlag.value = true
                    channel.send(UiEvent.Message("已保存修改，可在操作历史撤销"))
                }
                .onFailure { channel.send(UiEvent.Message(it.message ?: "保存失败", NoticeKind.Error)) }
        }
    }

    companion object {
        fun factory(billId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BillEditViewModel(
                    billId = billId,
                    billRepository = Graph.billRepository,
                    categoryRepository = Graph.categoryRepository,
                    tagRepository = Graph.tagRepository,
                    editBillUseCase = Graph.editBillUseCase,
                )
            }
        }
    }
}