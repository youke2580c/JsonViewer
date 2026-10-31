package com.guaner.jsonviewer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsOrderTest {

    @Test
    fun defaultOrderCoversAllButtons() {
        assertEquals(AppSettings.CONFIG_KEYS, AppSettings.normalize(AppSettings.DEFAULT_ORDER, AppSettings.CONFIG_KEYS))
    }

    @Test
    fun customOrderIsKept() {
        assertEquals(
            // 缺失项（save/share）按默认顺序补在末尾，保证每个按钮都有位置
            // 输入里没有 undo/redo/save/share 等，按默认顺序补在末尾
            listOf(
                "clear", "import", "escape", "minify", "beautify", "bottom",
                "top", "paste", "jsonpath", "search", "outline",
                "undo", "redo", "save", "share", "history", "network"
            ),
            AppSettings.normalize(
                "clear,import,escape,minify,beautify,bottom,top,paste,jsonpath,search,outline",
                AppSettings.CONFIG_KEYS
            )
        )
    }

    @Test
    fun unknownAndDuplicateKeysAreDropped() {
        val order = AppSettings.normalize("top,top,foo,search", AppSettings.CONFIG_KEYS)
        // 未知 key 与重复项被丢弃，缺失项按默认顺序补在末尾
        assertEquals("top", order[0])
        assertEquals("search", order[1])
        assertEquals(AppSettings.CONFIG_KEYS.size, order.size)
        assertEquals(order.distinct(), order)
        assertEquals(AppSettings.CONFIG_KEYS.toSet(), order.toSet())
    }

    @Test
    fun blankInputFallsBackToDefault() {
        assertEquals(AppSettings.CONFIG_KEYS, AppSettings.normalize("", AppSettings.CONFIG_KEYS))
        assertEquals(AppSettings.CONFIG_KEYS, AppSettings.normalize(null, AppSettings.CONFIG_KEYS))
        assertEquals(AppSettings.CONFIG_KEYS, AppSettings.normalize(" , , ", AppSettings.CONFIG_KEYS))
    }

    @Test
    fun defaultHiddenKeepsBasicButtonsVisible() {
        val hidden = AppSettings.filterValid(AppSettings.DEFAULT_HIDDEN, AppSettings.CONFIG_KEYS)
        assertTrue(hidden.contains("beautify"))
        assertTrue(hidden.contains("clear"))
        // 默认显示的 6 项不能被隐藏，否则底部栏一开始就只剩「更多」
        for (key in listOf("outline", "search", "jsonpath", "paste", "top", "bottom")) {
            assertTrue(key !in hidden)
        }
    }

    @Test
    fun catalogMatchesSettingsKeys() {
        // 目录与设置里的 key 必须一一对应，否则重排会丢按钮
        assertEquals(AppSettings.CONFIG_KEYS, com.guaner.jsonviewer.ui.BottomActionCatalog.CONFIG.map { it.key })
        assertTrue(com.guaner.jsonviewer.ui.BottomActionCatalog.CONFIG.all { it.icon != 0 })
    }

    @Test
    fun outlineSwipeFallsBackToSameSide() {
        assertEquals(AppSettings.SWIPE_SAME, AppSettings.normalizeSwipe(null))
        assertEquals(AppSettings.SWIPE_SAME, AppSettings.normalizeSwipe(""))
        assertEquals(AppSettings.SWIPE_SAME, AppSettings.normalizeSwipe("left"))
        assertEquals(AppSettings.SWIPE_BOTH, AppSettings.normalizeSwipe("both"))
        assertEquals(AppSettings.SWIPE_OFF, AppSettings.normalizeSwipe("off"))
    }

    @Test
    fun outlineSideFallsBackToLeft() {
        assertEquals(AppSettings.SIDE_LEFT, AppSettings.normalizeSide(null))
        assertEquals(AppSettings.SIDE_LEFT, AppSettings.normalizeSide(""))
        assertEquals(AppSettings.SIDE_LEFT, AppSettings.normalizeSide("top"))
        // 只有明确写了 right 才放到右边，避免脏值把面板弄丢
        assertEquals(AppSettings.SIDE_RIGHT, AppSettings.normalizeSide("right"))
    }

    @Test
    fun resetToDefaultRestoresAllButtons() {
        // 重置后：全部项都有位置，默认隐藏 5 项，其余全部显示在底部栏（另加固定的「更多」）。
        // 这里不写死数字：以后再加按钮不用回来改期望值
        val order = AppSettings.normalize(AppSettings.DEFAULT_ORDER, AppSettings.CONFIG_KEYS)
        val hidden = AppSettings.filterValid(AppSettings.DEFAULT_HIDDEN, AppSettings.CONFIG_KEYS).toSet()
        assertEquals(AppSettings.CONFIG_KEYS.size, order.size)
        assertEquals(AppSettings.CONFIG_KEYS.size - hidden.size, order.count { it !in hidden })
        // 撤销 / 重做默认就在底栏上（这两个是最常用的编辑动作）
        assertTrue("undo" !in hidden)
        assertTrue("redo" !in hidden)
    }

    /**
     * 「更多」菜单开关的 key 集合：
     * 只能有 about / sample，「设置」永远不能进来——否则用户把它关掉就再也回不去设置了。
     */
    @Test
    fun menuKeysAreAboutAndSampleOnly() {
        assertEquals(listOf("about", "sample"), AppSettings.MENU_KEYS)
        assertTrue("settings" !in AppSettings.MENU_KEYS)
    }

    /** 三态循环：底栏 → 更多 → 隐藏 → 底栏 */
    @Test
    fun placeCycleGoesBottomMoreHidden() {
        assertEquals(AppSettings.PLACE_MORE, AppSettings.nextPlace(AppSettings.PLACE_BOTTOM))
        assertEquals(AppSettings.PLACE_HIDDEN, AppSettings.nextPlace(AppSettings.PLACE_MORE))
        assertEquals(AppSettings.PLACE_BOTTOM, AppSettings.nextPlace(AppSettings.PLACE_HIDDEN))
    }

    @Test
    fun placeOfReadsBothSets() {
        assertEquals(AppSettings.PLACE_BOTTOM, AppSettings.placeOf("top", emptySet(), emptySet()))
        assertEquals(AppSettings.PLACE_MORE, AppSettings.placeOf("top", setOf("top"), emptySet()))
        assertEquals(AppSettings.PLACE_HIDDEN, AppSettings.placeOf("top", setOf("top"), setOf("top")))
    }

    /** 两个集合必须始终满足 menuHidden ⊆ bottomHidden，否则会出现「在底部栏却不在菜单里」的脏状态 */
    @Test
    fun applyPlaceKeepsBothSetsConsistent() {
        val bottomHidden = mutableSetOf<String>()
        val menuHidden = mutableSetOf<String>()

        AppSettings.applyPlace("outline", AppSettings.PLACE_MORE, bottomHidden, menuHidden)
        assertEquals(setOf("outline"), bottomHidden)
        assertTrue(menuHidden.isEmpty())

        AppSettings.applyPlace("outline", AppSettings.PLACE_HIDDEN, bottomHidden, menuHidden)
        assertEquals(setOf("outline"), bottomHidden)
        assertEquals(setOf("outline"), menuHidden)

        // 隐藏 → 底栏：两个集合都要清掉，否则回到底部栏却还留在「已隐藏」里
        AppSettings.applyPlace("outline", AppSettings.PLACE_BOTTOM, bottomHidden, menuHidden)
        assertTrue(bottomHidden.isEmpty())
        assertTrue(menuHidden.isEmpty())
    }

    @Test
    fun hiddenMenuDropsUnknownKeysAndDuplicates() {
        // 脏值（未知 key、重复项、空项）一律收敛，避免菜单项莫名消失
        assertEquals(
            listOf("about", "sample"),
            AppSettings.filterValid("about,bogus,sample,about, settings ,", AppSettings.MENU_KEYS)
        )
        assertEquals(emptyList<String>(), AppSettings.filterValid("", AppSettings.MENU_KEYS))
    }
}
