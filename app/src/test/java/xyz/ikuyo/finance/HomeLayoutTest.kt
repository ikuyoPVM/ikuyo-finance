package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.domain.model.HomeLayout
import xyz.ikuyo.finance.domain.model.HomeModule

class HomeLayoutTest {

    @Test
    fun defaultOrderIsAllModules() {
        val layout = HomeLayout()
        assertEquals(HomeModule.entries.toList(), layout.order)
        assertEquals(layout.order, layout.visibleModules)
    }

    @Test
    fun moveUpSwapsWithPrevious() {
        val layout = HomeLayout()
        val moved = layout.moveUp(HomeModule.STATS)
        assertEquals(
            listOf(
                HomeModule.BALANCE,
                HomeModule.STATS,
                HomeModule.ACTIONS,
                HomeModule.BUDGET,
                HomeModule.RECENT,
            ),
            moved.order,
        )
    }

    @Test
    fun moveUpAtTopIsNoOp() {
        val layout = HomeLayout()
        val moved = layout.moveUp(HomeModule.BALANCE)
        assertSame(layout, moved)
    }

    @Test
    fun moveDownSwapsWithNext() {
        val layout = HomeLayout()
        val moved = layout.moveDown(HomeModule.BALANCE)
        assertEquals(HomeModule.ACTIONS, moved.order.first())
        assertEquals(HomeModule.BALANCE, moved.order[1])
    }

    @Test
    fun moveDownAtBottomIsNoOp() {
        val layout = HomeLayout()
        val moved = layout.moveDown(HomeModule.RECENT)
        assertSame(layout, moved)
    }

    @Test
    fun hiddenModuleLeavesVisibleListButKeepsOrder() {
        val layout = HomeLayout().toggleHidden(HomeModule.ACTIONS)
        assertFalse(layout.isVisible(HomeModule.ACTIONS))
        assertEquals(
            listOf(HomeModule.BALANCE, HomeModule.STATS, HomeModule.BUDGET, HomeModule.RECENT),
            layout.visibleModules,
        )
        assertEquals(5, layout.order.size)
    }

    @Test
    fun toggleHiddenTwiceRestores() {
        val layout = HomeLayout()
        val restored = layout.toggleHidden(HomeModule.RECENT).toggleHidden(HomeModule.RECENT)
        assertEquals(layout.hidden, restored.hidden)
        assertTrue(restored.isVisible(HomeModule.RECENT))
    }

    @Test
    fun toggleCollapsedIsIndependentOfHidden() {
        val layout = HomeLayout().toggleCollapsed(HomeModule.BUDGET)
        assertTrue(layout.isCollapsed(HomeModule.BUDGET))
        assertTrue(layout.isVisible(HomeModule.BUDGET))
    }

    @Test
    fun normalizeDropsUnknownAndAppendsMissing() {
        val normalized = HomeModule.normalize(listOf("stats", "unknown", "balance"))
        assertEquals(HomeModule.STATS, normalized.first())
        assertEquals(HomeModule.BALANCE, normalized[1])
        assertEquals(HomeModule.entries.size, normalized.size)
        assertEquals(normalized.size, normalized.distinct().size)
    }

    @Test
    fun normalizeDeduplicates() {
        val normalized = HomeModule.normalize(listOf("balance", "balance", "balance"))
        assertEquals(HomeModule.entries.size, normalized.size)
        assertEquals(HomeModule.BALANCE, normalized.first())
    }

    @Test
    fun normalizeOfEmptyIsDefaultOrder() {
        assertEquals(HomeModule.entries.toList(), HomeModule.normalize(emptyList()))
    }

    @Test
    fun fromKeyResolvesKnownAndRejectsUnknown() {
        assertEquals(HomeModule.BUDGET, HomeModule.fromKey("budget"))
        assertEquals(null, HomeModule.fromKey("Budget"))
    }
}