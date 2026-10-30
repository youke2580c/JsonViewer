package com.guaner.jsonviewer.json

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 值弹窗编辑保存前的片段校验：宁可不保存，也不要把整份 JSON 弄坏 */
class JsonValueValidatorTest {

    @Test
    fun acceptsSingleValues() {
        assertTrue(JsonValueValidator.isValid("1"))
        assertTrue(JsonValueValidator.isValid(" -1.5e3 "))
        assertTrue(JsonValueValidator.isValid("\"abc\""))
        assertTrue(JsonValueValidator.isValid("true"))
        assertTrue(JsonValueValidator.isValid("null"))
        assertTrue(JsonValueValidator.isValid("{}"))
        assertTrue(JsonValueValidator.isValid("[]"))
        assertTrue(JsonValueValidator.isValid("""{"a":[1,{"b":null}]}"""))
    }

    @Test
    fun rejectsTrailingContent() {
        // 两个值拼在一起是最常见的「粘贴多了」错误
        assertFalse(JsonValueValidator.isValid("1 2"))
        assertFalse(JsonValueValidator.isValid("""{"a":1} {"b":2}"""))
        assertFalse(JsonValueValidator.isValid("true false"))
    }

    @Test
    fun rejectsIncompleteOrInvalid() {
        assertFalse(JsonValueValidator.isValid(null))
        assertFalse(JsonValueValidator.isValid(""))
        assertFalse(JsonValueValidator.isValid("   "))
        assertFalse(JsonValueValidator.isValid("{"))
        assertFalse(JsonValueValidator.isValid("[1,"))
        assertFalse(JsonValueValidator.isValid("abc"))
        assertFalse(JsonValueValidator.isValid("{'a':1}"))
    }

    @Test
    fun numberRules() {
        assertTrue(JsonValueValidator.isNumber("0"))
        assertTrue(JsonValueValidator.isNumber("-12"))
        assertTrue(JsonValueValidator.isNumber("1.5"))
        assertTrue(JsonValueValidator.isNumber("1e-3"))
        // JSON 不允许前导 0 / 前导 + / 单独的 -
        assertFalse(JsonValueValidator.isNumber("012"))
        assertFalse(JsonValueValidator.isNumber("+1"))
        assertFalse(JsonValueValidator.isNumber("-"))
        assertFalse(JsonValueValidator.isNumber("1."))
        assertFalse(JsonValueValidator.isNumber(null))
    }
}
