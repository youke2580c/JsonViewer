package com.guaner.jsonviewer.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：JsonHighlighter.findTokens 对所有 token 必须保证下标合法（start < end <= length）。
 * 此前 KEY token 的结束下标只算了「匹配串内部相对下标」，未换算为绝对下标，
 * 导致非首位的键出现 start > end，SpannableString.setSpan 抛出 IndexOutOfBoundsException 直接闪退。
 */
class JsonHighlighterTest {

    @Test
    fun allTokens_haveValidBounds() {
        val samples = listOf(
            """{"a":1,"b":2}""",
            """{
  "store": {
    "book": [
      { "category": "fiction", "author": "Tolkien", "price": 22.99 }
    ],
    "bicycle": { "color": "red", "price": 19.95 }
  },
  "expensive": 10,
  "available": true,
  "note": null
}""",
            """{"nested":{"deep":{"key":[1,2,3]}}}"""
        )
        for (text in samples) {
            val tokens = JsonHighlighter.findTokens(text)
            for (t in tokens) {
                assertTrue("token start >= 0 for $t in [$text]", t.start >= 0)
                assertTrue("token end > start for $t in [$text]", t.end > t.start)
                assertTrue("token end <= length for $t in [$text]", t.end <= text.length)
            }
        }
    }

    @Test
    fun keyToken_notAtStart_hasAbsoluteEndBeyondStart() {
        // 第一个键不在文本开头：验证 end 为绝对下标，避免 start>end 触发 IndexOutOfBounds
        val text = """{
  "store": { "book": [ { "category": "x" } ] }
}"""
        val tokens = JsonHighlighter.findTokens(text)
        val firstKey = tokens.first { it.kind == TokenKind.KEY }
        assertTrue("first key start should be > 0 (not at position 0)", firstKey.start > 0)
        assertTrue("first key end must be > start", firstKey.end > firstKey.start)
    }
}
