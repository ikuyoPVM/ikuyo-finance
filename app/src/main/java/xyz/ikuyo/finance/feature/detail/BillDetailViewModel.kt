package xyz.ikuyo.finance.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.data.repository.CategoryRepository
import xyz.ikuyo.finance.data.repository.OperationRepository
import xyz.ikuyo.finance.data.repository.TagRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.BillOperation
import xyz.ikuyo.finance.domain.model.BillOperationType
import xyz.ikuyo.finance.domain.model.Category
import xyz.ikuyo.finance.domain.model.Tag

data class BillDetailUiState(
    val isLoading: Boolean = true,
    val bill: Bill? = null,
    val billTags: List<Tag> = emptyList(),
    val operations: List<BillOperation> = emptyList(),
    val categories: List<Category> = emptyList(),
    val allTags: List<Tag> = emptyList(),
) {
    val isMissing: Boolean get() = !isLoading && bill == null
}

/** 账单详情：只读展示 + 编辑入口 + 删除（软删除，进回收站）。 */
class BillDetailViewModel(
    private val billId: Long,
    private val billRepository: BillRepository,
    tagRepository: TagRepository,
    categoryRepository: CategoryRepository,
    private val operationRepository: OperationRepository,
) : ViewModel() {

    private val channel = Channel<UiEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    val state = combine(
        billRepository.observeActive(),
        tagRepository.observeAll(),
        tagRepository.observeAllLinks(),
        operationRepository.observeForBill(billId),
        categoryRepository.observeAll(),
    ) { bills, tags, links, operations, categories ->
        val bill = bills.firstOrNull { it.id == billId }
        val mine = links.filter { it.billId == billId }.map { it.tagId }.toSet()
        BillDetailUiState(
            isLoading = false,
            bill = bill,
            billTags = tags.filter { it.id in mine },
            operations = operations,
            categories = categories,
            allTags = tags,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BillDetailUiState())

    fun delete() {
        val bill = state.value.bill ?: return
        viewModelScope.launch {
            billRepository.softDelete(bill.id)
            operationRepository.record(
                billId = bill.id,
                type = BillOperationType.DELETE,
                summary = OperationRepository.summaryOf(BillOperationType.DELETE, bill),
                amountMinor = bill.cnyAmountMinor,
                before = bill,
                operatedAt = Time.nowMillis(),
            )
            channel.send(UiEvent.Message("已移入回收站，可在回收站恢复"))
        }
    }

    companion object {
        fun factory(billId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BillDetailViewModel(
                    billId = billId,
                    billRepository = Graph.billRepository,
                    tagRepository = Graph.tagRepository,
                    categoryRepository = Graph.categoryRepository,
                    operationRepository = Graph.operationRepository,
                )
            }
        }
    }
}