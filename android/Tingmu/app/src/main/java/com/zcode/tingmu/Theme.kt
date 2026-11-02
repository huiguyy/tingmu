package com.zcode.tingmu

import android.graphics.Color
import java.io.File

/** 外观主题：背景色 + 文字色。默认白底黑字（用户规格）。 */
data class Theme(val bg: Int, val fg: Int)

val THEMES = listOf(
    Theme(Color.WHITE, Color.BLACK),
    Theme(Color.BLACK, Color.WHITE),
    Theme(0xFF101418.toInt(), 0xFFFFD60A.toInt()),
    Theme(Color.BLACK, 0xFF4ADE80.toInt()),
)

val FONT_SIZES = floatArrayOf(28f, 36f, 44f, 54f, 66f)

/** 端侧可用模型；id 形如 "small-ctc-zh-2025" 或外挂目录名。 */
data class ModelEntry(
    val id: String,
    val dir: File,
    val isCtc: Boolean,
    val label: String,
)
