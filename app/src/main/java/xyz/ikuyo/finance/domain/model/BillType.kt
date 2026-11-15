package xyz.ikuyo.finance.domain.model

/** 账单类型：收入 / 支出 / 退款。退款冲减原消费，不计普通收入。 */
enum class BillType(val label: String, val dbValue: String) {
    EXPENSE("支出", "EXPENSE"),
    INCOME("收入", "INCOME"),
    REFUND("退款", "REFUND");

    companion object {
        fun fromDb(value: String): BillType = entries.firstOrNull { it.dbValue == value } ?: EXPENSE
    }
}

/** 账单来源：手动录入 / AI 解析。 */
enum class BillSource(val label: String, val dbValue: String) {
    MANUAL("手动", "MANUAL"),
    AI("AI", "AI");

    companion object {
        fun fromDb(value: String): BillSource = entries.firstOrNull { it.dbValue == value } ?: MANUAL
    }
}

enum class PaymentMethod(val label: String, val dbValue: String) {
    WECHAT("微信", "WECHAT"),
    ALIPAY("支付宝", "ALIPAY"),
    BANK_CARD("银行卡", "BANK_CARD"),
    CASH("现金", "CASH"),
    OTHER("其他", "OTHER");

    companion object {
        fun fromDb(value: String): PaymentMethod =
            entries.firstOrNull { it.dbValue == value } ?: OTHER
    }
}

enum class BillSortOrder(val label: String, val dbValue: String) {
    OCCURRED_DESC("按消费时间", "OCCURRED_DESC"),
    CREATED_DESC("按录入时间", "CREATED_DESC");

    companion object {
        fun fromDb(value: String): BillSortOrder =
            entries.firstOrNull { it.dbValue == value } ?: OCCURRED_DESC
    }
}