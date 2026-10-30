package com.guaner.jsonviewer.ui

import com.guaner.jsonviewer.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 底部按钮的取名规则：两字短名 + 完整名称 + 长按说明 */
class BottomActionCatalogTest {

    @Test
    fun everyLabelIsTwoChars() {
        // 底部栏只有图标，长按提示以短名开头，所以短名统一两个汉字
        BottomActionCatalog.CONFIG.forEach {
            assertEquals("${it.key} 的短名应为两个字", 2, it.label.length)
        }
        assertEquals(2, BottomActionCatalog.MORE.label.length)
    }

    @Test
    fun labelsAreUnique() {
        val labels = BottomActionCatalog.CONFIG.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun everyActionHasFullNameAndDescription() {
        BottomActionCatalog.CONFIG.forEach {
            assertTrue("${it.key} 缺完整名称", it.full.isNotBlank())
            assertTrue("${it.key} 缺长按说明", it.desc.isNotBlank())
            assertTrue("${it.key} 没有图标", it.icon != 0)
        }
        // 完整名称同样不能重复
        val fulls = BottomActionCatalog.CONFIG.map { it.full }
        assertEquals(fulls.size, fulls.toSet().size)
    }

    @Test
    fun longPressHintStartsWithLabel() {
        val outline = BottomActionCatalog.action("outline")!!
        val hint = BottomActionCatalog.longPressHint(outline)
        assertTrue(hint.startsWith("大纲 · "))
        assertTrue(hint.length > outline.label.length + 3)
    }

    @Test
    fun keysMatchSettingsList() {
        assertEquals(AppSettings.CONFIG_KEYS, BottomActionCatalog.CONFIG.map { it.key })
    }

    @Test
    fun unknownKeyFallsBackSafely() {
        assertEquals("xxx", BottomActionCatalog.label("xxx"))
        assertEquals("", BottomActionCatalog.desc("xxx"))
        assertTrue(BottomActionCatalog.icon("xxx") != 0)
    }
}
