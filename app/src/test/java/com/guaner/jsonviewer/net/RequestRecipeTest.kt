package com.guaner.jsonviewer.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRecipeTest {

    @Test
    fun `GET 带请求头`() {
        val input = "https://api.lofter.com/newsearch/post.json?key=我的&sortType=0&limit=20," +
            "{\"headers\":{\"Content-Type\":\"application/x-www-form-urlencoded;charset=utf-8\"," +
            "\"deviceid\":\"60dc19c30ad792b8\"}}"
        val recipe = RequestRecipeParser.parse(input)!!
        assertEquals("https://api.lofter.com/newsearch/post.json?key=我的&sortType=0&limit=20", recipe.url)
        assertNull(recipe.method)
        assertEquals(2, recipe.headers.size)
        assertEquals("application/x-www-form-urlencoded;charset=utf-8", recipe.headers[0].value)
        assertEquals("deviceid", recipe.headers[1].name)
    }

    @Test
    fun `显式 GET`() {
        val recipe = RequestRecipeParser.parse(
            "https://a.com/x,{\"method\":\"GET\",\"headers\":{\"A\":\"1\"}}"
        )!!
        assertEquals("GET", recipe.method)
        assertEquals("1", recipe.headers.single().value)
    }

    @Test
    fun `POST 带请求体`() {
        val recipe = RequestRecipeParser.parse(
            "https://a.com/x,{\"method\":\"POST\",\"body\":\"key=我的&t=1\",\"headers\":{\"A\":\"1\"}}"
        )!!
        assertEquals("POST", recipe.method)
        assertEquals("key=我的&t=1", recipe.body)
    }

    @Test
    fun `charset 字段`() {
        val recipe = RequestRecipeParser.parse(
            "https://a.com/x,{\"charset\":\"gbk\",\"method\":\"POST\",\"body\":\"a=1\"}"
        )!!
        assertEquals("gbk", recipe.charset)
    }

    @Test
    fun `不支持的编码被忽略`() {
        val recipe = RequestRecipeParser.parse("https://a.com/x,{\"charset\":\"not-a-charset\"}")!!
        assertNull(recipe.charset)
    }

    @Test
    fun `URL 里的逗号不误拆`() {
        val url = "https://a.com/x?list=1,2,3&q=a,b"
        val recipe = RequestRecipeParser.parse("$url,{\"method\":\"GET\"}")!!
        assertEquals(url, recipe.url)
    }

    @Test
    fun `没有配置段时返回 null`() {
        assertNull(RequestRecipeParser.parse("https://a.com/x?q=1,2"))
        assertNull(RequestRecipeParser.parse("https://a.com/x"))
    }

    @Test
    fun `非法 JSON 返回 null`() {
        assertNull(RequestRecipeParser.parse("https://a.com/x,{\"method\""))
        assertNull(RequestRecipeParser.parse("https://a.com/x,{bad json}"))
    }

    @Test
    fun `不含配置键的对象不拆`() {
        assertNull(RequestRecipeParser.parse("https://a.com/x,{\"foo\":1}"))
    }

    @Test
    fun `请求头数组写法`() {
        val recipe = RequestRecipeParser.parse(
            "https://a.com/x,{\"headers\":[{\"name\":\"A\",\"value\":\"1\"},[\"B\",\"2\"]]}"
        )!!
        assertEquals(listOf("A", "B"), recipe.headers.map { it.name })
        assertEquals(listOf("1", "2"), recipe.headers.map { it.value })
    }

    @Test
    fun `对象型 body 压成 JSON`() {
        val recipe = RequestRecipeParser.parse("https://a.com/x,{\"body\":{\"a\":1}}")!!
        assertEquals("{\"a\":1}", recipe.body)
    }

    @Test
    fun `结尾空格与多级逗号`() {
        val recipe = RequestRecipeParser.parse(" https://a.com/x , {\"method\":\"GET\"} ")!!
        assertEquals("https://a.com/x", recipe.url)
        assertTrue(recipe.headers.isEmpty())
    }
}
