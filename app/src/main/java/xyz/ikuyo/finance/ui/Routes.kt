package xyz.ikuyo.finance.ui

/** 全部导航路由。底部导航只使用 [TABS] 中的三个。 */
object Routes {
    const val HOME = "home"
    const val AI = "ai"
    const val MINE = "mine"

    const val BILLS = "bills"
    const val TRASH = "trash"
    const val BUDGET = "budget"
    const val CATEGORIES = "categories"

    val TABS: Set<String> = setOf(HOME, AI, MINE)
}