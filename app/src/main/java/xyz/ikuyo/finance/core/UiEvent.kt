package xyz.ikuyo.finance.core

/** 一次性 UI 事件（Snackbar 等），避免状态流重复消费。 */
sealed interface UiEvent {
    data class Message(
        val text: String,
        val undoable: Boolean = false,
    ) : UiEvent
}