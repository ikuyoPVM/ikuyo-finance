package xyz.ikuyo.finance.core

/**
 * 应用内提示的语义分级。
 *
 * 只描述「这条消息要传达什么」，不携带任何样式；具体配色与动效由 [xyz.ikuyo.finance.ui.notice] 决定，
 * 这样 ViewModel 不必依赖 Compose，提示外观也只需要在一处调整。
 */
enum class NoticeKind {
    /** 操作已完成，例如保存、恢复、移入回收站。 */
    Success,

    /** 中性提示，例如「已撤销上一笔」这类带动作的消息。 */
    Info,

    /** 需要用户注意但没出错，例如预算接近上限、模型列表需要重新获取。 */
    Warning,

    /** 操作失败，必须让用户看见，例如保存失败、网络错误。 */
    Error,
}