package xyz.ikuyo.finance.domain.model

/** 首页可自定义的模块，顺序与显隐由用户在「首页布局」里决定。 */
enum class HomeModule(val key: String, val label: String) {
    BALANCE("balance", "总余额"),
    ACTIONS("actions", "快捷记账"),
    STATS("stats", "消费统计"),
    BUDGET("budget", "预算概览"),
    RECENT("recent", "最近账单"),
    ;

    companion object {
        fun fromKey(key: String): HomeModule? = entries.firstOrNull { it.key == key }

        /** 数据里出现未知/缺失的 key 时，用默认顺序补齐并去重。 */
        fun normalize(keys: List<String>): List<HomeModule> {
            val known = keys.mapNotNull { fromKey(it) }.distinct()
            return known + entries.filter { it !in known }
        }
    }
}

data class HomeLayout(
    val order: List<HomeModule> = HomeModule.entries.toList(),
    val hidden: Set<HomeModule> = emptySet(),
    val collapsed: Set<HomeModule> = emptySet(),
) {
    val visibleModules: List<HomeModule> get() = order.filter { it !in hidden }

    fun isVisible(module: HomeModule): Boolean = module !in hidden

    fun isCollapsed(module: HomeModule): Boolean = module in collapsed

    /** 上移一格；已在最前则原样返回。 */
    fun moveUp(module: HomeModule): HomeLayout {
        val index = order.indexOf(module)
        if (index <= 0) return this
        val next = order.toMutableList()
        next.removeAt(index)
        next.add(index - 1, module)
        return copy(order = next)
    }

    /** 下移一格；已在最后则原样返回。 */
    fun moveDown(module: HomeModule): HomeLayout {
        val index = order.indexOf(module)
        if (index < 0 || index >= order.lastIndex) return this
        val next = order.toMutableList()
        next.removeAt(index)
        next.add(index + 1, module)
        return copy(order = next)
    }

    fun toggleHidden(module: HomeModule): HomeLayout =
        copy(hidden = if (module in hidden) hidden - module else hidden + module)

    fun toggleCollapsed(module: HomeModule): HomeLayout =
        copy(collapsed = if (module in collapsed) collapsed - module else collapsed + module)
}