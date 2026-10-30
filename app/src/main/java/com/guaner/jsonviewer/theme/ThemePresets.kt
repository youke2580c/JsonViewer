package com.guaner.jsonviewer.theme

import android.graphics.Color

/**
 * 内置主题预设：每套主色 / 背景 / 图标色（accent）均不同，可一键切换。
 * 语法高亮色（jsonKey 等）已为各自背景优化（深色主题用更亮的荧光色）。
 */
data class ThemeColors(
    val brand: Int,
    val background: Int,
    val surface: Int,
    val text: Int,
    val gutter: Int,
    val gutterText: Int,
    val jsonKey: Int,
    val jsonString: Int,
    val jsonNumber: Int,
    val jsonBoolean: Int,
    val jsonPunct: Int,
    val jsonNull: Int,
    val accent: Int,
    val errorLine: Int,
    val errorColumn: Int,
    val searchMatch: Int,
    val searchCurrent: Int,
    val treeSelected: Int,
    val folderObject: Int,
    val folderArray: Int
) {
    /** 用于主题选择卡片预览的几个代表性色 */
    fun previewColors(): List<Int> = listOf(brand, background, jsonKey, jsonString, jsonNumber)
}

object ThemePresets {

    const val DEFAULT_ID = "classic_blue"

    private fun c(hex: String) = Color.parseColor(hex)

    val classicBlue = ThemeColors(
        brand = c("#2E6BE6"), background = c("#FFFFFF"), surface = c("#F5F7FA"),
        text = c("#1F2933"), gutter = c("#EEF1F5"), gutterText = c("#98A2B3"),
        jsonKey = c("#7C3AED"), jsonString = c("#0B7A4B"), jsonNumber = c("#B45309"),
        jsonBoolean = c("#DC2626"), jsonPunct = c("#9CA3AF"), jsonNull = c("#6B7280"),
        accent = c("#2E6BE6"), errorLine = c("#FDE2E2"), errorColumn = c("#FCA5A5"),
        searchMatch = c("#FDE68A"), searchCurrent = c("#F59E0B"), treeSelected = c("#D6E4FF"),
        folderObject = c("#2E6BE6"), folderArray = c("#E08600")
    )

    val darkNight = ThemeColors(
        brand = c("#3B82F6"), background = c("#121212"), surface = c("#1A1F26"),
        text = c("#D5DAE1"), gutter = c("#1A1F26"), gutterText = c("#7A8494"),
        jsonKey = c("#C4B5FD"), jsonString = c("#6EE7B7"), jsonNumber = c("#FCD34D"),
        jsonBoolean = c("#FCA5A5"), jsonPunct = c("#6B7280"), jsonNull = c("#9CA3AF"),
        accent = c("#3B82F6"), errorLine = c("#442222"), errorColumn = c("#7F1D1D"),
        searchMatch = c("#4A3B12"), searchCurrent = c("#B45309"), treeSelected = c("#1E3A5F"),
        folderObject = c("#6AA0FF"), folderArray = c("#F0A93C")
    )

    val mintGreen = ThemeColors(
        brand = c("#00897B"), background = c("#F1F8F6"), surface = c("#E3F0EC"),
        text = c("#1A2E2A"), gutter = c("#E3F0EC"), gutterText = c("#5C7A72"),
        jsonKey = c("#6A1B9A"), jsonString = c("#0B7A4B"), jsonNumber = c("#B45309"),
        jsonBoolean = c("#C62828"), jsonPunct = c("#7A8C86"), jsonNull = c("#5C7A72"),
        accent = c("#00897B"), errorLine = c("#FDE2E2"), errorColumn = c("#EF9A9A"),
        searchMatch = c("#FDE68A"), searchCurrent = c("#F59E0B"), treeSelected = c("#C8E6E0"),
        folderObject = c("#00897B"), folderArray = c("#E08600")
    )

    val warmOrange = ThemeColors(
        brand = c("#E65100"), background = c("#FFF8F0"), surface = c("#FBEEDF"),
        text = c("#2B1D12"), gutter = c("#FBEEDF"), gutterText = c("#9A7B5E"),
        jsonKey = c("#7C3AED"), jsonString = c("#0B7A4B"), jsonNumber = c("#B45309"),
        jsonBoolean = c("#C62828"), jsonPunct = c("#A89078"), jsonNull = c("#9A7B5E"),
        accent = c("#E65100"), errorLine = c("#FDE2E2"), errorColumn = c("#EF9A9A"),
        searchMatch = c("#FDE68A"), searchCurrent = c("#F59E0B"), treeSelected = c("#FCE0C2"),
        folderObject = c("#E65100"), folderArray = c("#D97706")
    )

    val sakuraPurple = ThemeColors(
        brand = c("#8E24AA"), background = c("#FBF4FC"), surface = c("#F3E6F7"),
        text = c("#2A1B2E"), gutter = c("#F3E6F7"), gutterText = c("#8B6E92"),
        jsonKey = c("#6A1B9A"), jsonString = c("#0B7A4B"), jsonNumber = c("#B45309"),
        jsonBoolean = c("#C62828"), jsonPunct = c("#A892B0"), jsonNull = c("#8B6E92"),
        accent = c("#8E24AA"), errorLine = c("#FDE2E2"), errorColumn = c("#EF9A9A"),
        searchMatch = c("#FDE68A"), searchCurrent = c("#F59E0B"), treeSelected = c("#EFD6F4"),
        folderObject = c("#8E24AA"), folderArray = c("#C026D3")
    )

    /** 主题列表（顺序即展示顺序），含中文名便于 UI 展示 */
    val presets: Map<String, ThemeColors> = mapOf(
        DEFAULT_ID to classicBlue,
        "dark_night" to darkNight,
        "mint_green" to mintGreen,
        "warm_orange" to warmOrange,
        "sakura_purple" to sakuraPurple
    )

    val presetNames: List<Pair<String, String>> = listOf(
        DEFAULT_ID to "默认蓝",
        "dark_night" to "暗夜黑",
        "mint_green" to "护眼青绿",
        "warm_orange" to "暖橙",
        "sakura_purple" to "樱粉紫"
    )
}
