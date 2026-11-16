package xyz.ikuyo.finance.core

/**
 * 一次性 UI 事件，避免状态流重复消费。
 *
 * 消息只有「文案 + 分级 + 是否可撤销」三件事，样式全部交给
 * [xyz.ikuyo.finance.ui.notice.AppSnackbarHost]，保证全应用提示长得一样。
 */
sealed interface UiEvent {
    data class Message(
        val text: String,
        val kind: NoticeKind = NoticeKind.Success,
        val undoable: Boolean = false,
    ) : UiEvent
}