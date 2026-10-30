package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSONPath 大结果集的截断防护 */
class JsonPathLimitTest {

    // 3000 个带 price 的对象，命中数超过 MAX_HITS 的一半不足以触发截断，
    // 用一个超过 5000 的查询来验证截断行为
    private fun bigJson(count: Int): String {
        val sb = StringBuilder("{\"items\":[")
        for (i in 0 until count) {
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(i).append(",\"price\":").append(i % 100).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    @Test
    fun `small result set is not truncated`() {
        val result = JsonPathEngine.query(bigJson(50), "$..price")
        assertTrue(result is QueryResult.Ok)
        val ok = result as QueryResult.Ok
        assertEquals(50, ok.hits.size)
        assertEquals(false, ok.truncated)
        assertEquals(50, ok.totalCount)
    }

    @Test
    fun `large result set is truncated to max hits`() {
        val result = JsonPathEngine.query(bigJson(6_000), "$..price")
        assertTrue(result is QueryResult.Ok)
        val ok = result as QueryResult.Ok
        assertTrue(ok.truncated)
        assertEquals(6_000, ok.totalCount)
        assertTrue(ok.hits.size <= 5_000)
        // 第一条命中应仍然正确
        assertEquals("0", ok.hits.first().full)
    }

    @Test
    fun `big subtree value is capped in full`() {
        val result = JsonPathEngine.query(bigJson(20_000), "$.items")
        assertTrue(result is QueryResult.Ok)
        val ok = result as QueryResult.Ok
        assertEquals(1, ok.hits.size)
        // 完整值被限制在 MAX_FULL_CHARS 附近（含收尾括号，单项略超一点）
        assertTrue(ok.hits.first().full.length <= 105_000)
        // 预览仍然很短
        assertTrue(ok.hits.first().preview.length <= 121)
    }

    @Test
    fun `query validates json first without building tree`() {
        val result = JsonPathEngine.query("""{"a":1 "b":2}""", "$.a")
        assertTrue(result is QueryResult.Err)
        assertTrue((result as QueryResult.Err).message.contains("语法错误"))
    }

    @Test
    fun `empty json reports empty instead of path error`() {
        val result = JsonPathEngine.query("   ", "$.a")
        assertTrue(result is QueryResult.Err)
        assertTrue((result as QueryResult.Err).message.contains("为空"))
    }
}
