package xyz.ikuyo.finance.feature.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.ikuyo.finance.core.Time
import xyz.ikuyo.finance.core.UiEvent
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.data.repository.TagRepository
import xyz.ikuyo.finance.di.Graph
import xyz.ikuyo.finance.domain.model.Bill
import xyz.ikuyo.finance.domain.model.Tag
import java.time.YearMonth

/** 单个标签在界面上的展示数据。 */
data class TagRow(
    val tag: Tag,
    val billCount: Int,
    val monthSpendMinor: Long,
)

data class TagsUiState(
    val isLoading: Boolean = true,
    val tags: List<TagRow> = emptyList(),
    val monthLabel: String = "",
) {
    val isEmpty: Boolean get() = !isLoading && tags.isEmpty()
}

/**
 * 标签管理。
 *
 * 标签只是账单的附加维度：删除标签只解除关联，账单与金额不受影响；
 * 统计口径与账单一致（退款冲减支出，收入不计入支出）。
 */
class TagsViewModel(
    private val tagRepository: TagRepository,
    private val billRepository: BillRepository,
) : ViewModel() {

    private val channel = Channel<UiEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    val state: StateFlow<TagsUiState> = combine(
        tagRepository.observeAll(),
        tagRepository.observeUsage(),
        billRepository.observeActive(),
        tagRepository.observeAllLinks(),
    ) { tags, usage, bills, links ->
        val month = YearMonth.now(Time.zone)
        val start = Time.startOfMonth(month)
        val end = Time.endOfMonthInclusive(month)
        val monthBills: Map<Long, Bill> = bills
            .filter { it.occurredAt in start..end }
            .associateBy { it.id }
        val spendByTag = mutableMapOf<Long, Long>()
        links.forEach { link ->
            val bill = monthBills[link.billId] ?: return@forEach
            spendByTag[link.tagId] = (spendByTag[link.tagId] ?: 0L) + bill.expenseImpactMinor
        }
        TagsUiState(
            isLoading = false,
            tags = tags.map { tag ->
                TagRow(
                    tag = tag,
                    billCount = usage[tag.id] ?: 0,
                    monthSpendMinor = spendByTag[tag.id] ?: 0L,
                )
            },
            monthLabel = Time.formatMonth(month),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TagsUiState())

    fun add(name: String, colorArgb: Int) {
        viewModelScope.launch {
            tagRepository.add(name, colorArgb)
                .onSuccess { emit("已添加标签：${name.trim()}") }
                .onFailure { emit(it.message ?: "添加标签失败") }
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            tagRepository.rename(id, name)
                .onSuccess { emit("标签已改名：${name.trim()}") }
                .onFailure { emit(it.message ?: "改名失败") }
        }
    }

    fun setColor(id: Long, colorArgb: Int) {
        viewModelScope.launch { tagRepository.setColor(id, colorArgb) }
    }

    fun delete(id: Long, name: String) {
        viewModelScope.launch {
            tagRepository.delete(id)
            emit("已删除标签：$name（只解除关联，账单保留）")
        }
    }

    private fun emit(text: String) {
        channel.trySend(UiEvent.Message(text))
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TagsViewModel(Graph.tagRepository, Graph.billRepository) }
        }
    }
}