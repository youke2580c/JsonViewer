package com.guaner.jsonviewer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义 UA 的编解码与命名：
 * UA 字符串里有空格、括号、分号、竖线，甚至可能被贴进换行，
 * 存储格式必须原样往返，坏数据也不能把整个列表带崩。
 */
class UaProfilesCodecTest {

    private val samples = listOf(
        UaProfile("手机 UA", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko)"),
        UaProfile("我的 UA", "A|B\tC\nD 中文 ；"),
        UaProfile("带 空 格", "x=1;y=2")
    )

    @Test
    fun roundTripKeepsNameAndValue() {
        val back = UaProfiles.decode(UaProfiles.encode(samples))
        assertEquals(samples.map { it.name }, back.map { it.name })
        assertEquals(samples.map { it.value }, back.map { it.value })
        assertTrue(back.all { it.custom })
    }

    @Test
    fun emptyInputGivesEmptyList() {
        assertTrue(UaProfiles.decode("").isEmpty())
        assertTrue(UaProfiles.decode("   ").isEmpty())
    }

    @Test
    fun brokenLinesAreSkipped() {
        // 缺制表符 / 名称为空 / 值解码失败，都应该跳过而不是整表丢失
        val raw = listOf(
            "没有分隔符",
            "\t只有值",
            "%E6%89%8B%E6%9C%BA\tUA-1",
            "坏行\t%ZZ"
        ).joinToString("\n")
        val back = UaProfiles.decode(raw)
        assertEquals(listOf("手机"), back.map { it.name })
        assertEquals(listOf("UA-1"), back.map { it.value })
    }

    @Test
    fun duplicateNamesAreDropped() {
        val raw = "A\t1\nA\t2"
        val back = UaProfiles.decode(raw)
        assertEquals(1, back.size)
        assertEquals("1", back[0].value)
    }

    @Test
    fun uniqueNameAppendsIndex() {
        val existing = listOf(
            UaProfile("手机 UA", "x"),
            UaProfile("我的 UA", "y"),
            UaProfile("我的 UA 2", "z")
        )
        assertEquals("我的 UA 3", UaProfiles.uniqueName("我的 UA", existing))
        assertEquals("全新 UA", UaProfiles.uniqueName("全新 UA", existing))
        // 「不携带」是保留名，用户起同样的名字要避开
        assertEquals("${UaProfiles.NONE} 2", UaProfiles.uniqueName(UaProfiles.NONE, existing))
    }

    @Test
    fun summaryCollapsesWhitespaceAndTruncates() {
        assertEquals("a b c", UaProfiles.summary("a\n b   c"))
        val long = "x".repeat(80)
        val short = UaProfiles.summary(long, max = 20)
        assertEquals(20, short.length)
        assertTrue(short.endsWith("…"))
    }
}
