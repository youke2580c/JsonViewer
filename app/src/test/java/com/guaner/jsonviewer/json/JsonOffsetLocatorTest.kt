package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JsonOffsetLocatorTest {

    private val json = """
        {
          "store": {
            "book": [
              { "title": "A", "price": 8.95 },
              { "title": "B", "price": 12.99 }
            ],
            "bicycle": { "color": "red" }
          },
          "escaped\"key": true,
          "note": n\u006Fl,
          "arr": [10, 20, {"deep": [1, 2, 3]}]
        }
    """.trimIndent()

    private fun rangeOf(path: String): String? {
        val r = JsonOffsetLocator.find(json, path) ?: return null
        return json.substring(r.first, r.last + 1)
    }

    @Test
    fun findsTopLevelKey() {
        assertEquals("store", rangeOf("$.store"))
    }

    @Test
    fun findsNestedKey() {
        assertEquals("bicycle", rangeOf("$.store.bicycle"))
        assertEquals("color", rangeOf("$.store.bicycle.color"))
    }

    @Test
    fun findsArrayElementObjectStart() {
        // 数组元素是对象时高亮起始括号
        assertEquals("{", rangeOf("$.store.book[1]"))
    }

    @Test
    fun findsKeyInsideArrayElement() {
        assertEquals("price", rangeOf("$.store.book[1].price"))
        // 对象成员定位到「键名」，数组元素定位到「元素值」，高亮位置更贴近用户预期
        assertEquals("title", rangeOf("$.store.book[1].title"))
    }

    @Test
    fun findsDeepArrayInObject() {
        assertEquals("deep", rangeOf("$.arr[2].deep"))
        assertEquals("20", rangeOf("$.arr[1]"))
    }

    @Test
    fun handlesEscapedKeyInPath() {
        assertEquals("escaped\\\"key", rangeOf("$['escaped\"key']"))
    }

    @Test
    fun returnsNullForMissingPath() {
        assertNull(JsonOffsetLocator.find(json, "$.store.nope"))
        assertNull(JsonOffsetLocator.find(json, "$.store.book[9]"))
        assertNull(JsonOffsetLocator.find(json, "$.store.book[0].nope"))
    }

    @Test
    fun rootPathPointsToStart() {
        assertNotNull(JsonOffsetLocator.find(json, "$"))
        assertEquals(0, JsonOffsetLocator.find(json, "$")?.first)
    }

    @Test
    fun parsePathKeepsEscapedKey() {
        val segs = JsonOffsetLocator.parsePath("$['a\\'b'][2].c")
        assertNotNull(segs)
        assertEquals(
            listOf(
                JsonOffsetLocator.Seg.Key("a'b"),
                JsonOffsetLocator.Seg.Index(2),
                JsonOffsetLocator.Seg.Key("c")
            ),
            segs
        )
    }

    // ---------- findValueRange：值编辑保存时替换的范围 ----------

    private val compact = """{"a":1,"s":"x,y:{}","o":{"k":[1,2]},"arr":[true,null,{"d":3}]}"""

    private fun valueOf(path: String): String? {
        val r = JsonOffsetLocator.findValueRange(compact, path) ?: return null
        return compact.substring(r.first, r.last + 1)
    }

    @Test
    fun valueRangeCoversScalar() {
        assertEquals("1", valueOf("$.a"))
    }

    @Test
    fun valueRangeCoversWholeStringWithQuotes() {
        // 字符串值必须带首尾引号，否则替换回去就少了引号
        assertEquals("\"x,y:{}\"", valueOf("$.s"))
    }

    @Test
    fun valueRangeCoversWholeContainer() {
        assertEquals("{\"k\":[1,2]}", valueOf("$.o"))
        assertEquals("{\"d\":3}", valueOf("$.arr[2]"))
    }

    @Test
    fun valueRangeCoversArrayElement() {
        assertEquals("true", valueOf("$.arr[0]"))
        assertEquals("null", valueOf("$.arr[1]"))
    }

    @Test
    fun valueRangeRejectsRoot() {
        // 根节点无法「原位替换」（就是整份文档），应返回 null 让上层提示
        assertNull(JsonOffsetLocator.findValueRange(compact, "$"))
        assertNull(JsonOffsetLocator.findValueRange(compact, "$.missing"))
    }

    @Test
    fun replacingValueKeepsDocumentValid() {
        val r = JsonOffsetLocator.findValueRange(compact, "$.o")!!
        val replaced = compact.substring(0, r.first) + """{"k":[9]}""" +
            compact.substring(r.last + 1)
        assertEquals("""{"a":1,"s":"x,y:{}","o":{"k":[9]},"arr":[true,null,{"d":3}]}""", replaced)
        // 替换后仍是合法 JSON（MainActivity 保存前会做同样校验）
        assertNotNull(JsonParser.parse(replaced) as? ParseResult.Success)
    }

    @Test
    fun keyWithColonOrBraceInsideStringDoesNotConfuseScanner() {
        val tricky = """{"a":"x:}{","b":{"c":1}}"""
        val r = JsonOffsetLocator.find(tricky, "$.b.c")
        assertNotNull(r)
        assertEquals("c", tricky.substring(r!!.first, r.last + 1))
    }
}
