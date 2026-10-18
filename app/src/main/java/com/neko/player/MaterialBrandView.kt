// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
// Adapted from HyperChanger MiuiBlendImageView.kt (5d84f123).
package com.neko.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/** Uses HyperChanger's MIUI advanced-material blend sequence when the OS provides it. */
internal class MaterialBrandView(context: Context) : FrameLayout(context) {
    private val logo = MaterialMaskView(context).apply {
        contentDescription = "Neko Player 图标"
        background = BrandMaskDrawables.cat(ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!)
    }
    private val wordmark = MaterialMaskView(context).apply {
        contentDescription = "Neko Player"
        background = BrandMaskDrawables.wordmark()
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(logo)
        addView(wordmark)
    }
    init {
        alpha = .85f
        clipChildren = false
        clipToPadding = false
        content.clipChildren = false
        content.clipToPadding = false
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }
    fun configure(logoSize: Int, textWidth: Int, dark: Boolean) {
        runCatching {
            View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType).invoke(this, 1)
            View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType).invoke(this, dp(50))
            View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType).invoke(this, 0)
        }
        content.translationY = -dp(40).toFloat()
        logo.applyMaterial(dark)
        wordmark.applyMaterial(dark)
        if (logo.layoutParams.width != logoSize) logo.layoutParams = LinearLayout.LayoutParams(logoSize, logoSize)
        val textHeight = (textWidth / 5.7f).toInt()
        if (wordmark.layoutParams.width != textWidth) {
            wordmark.layoutParams = LinearLayout.LayoutParams(textWidth, textHeight).apply { topMargin = dp(14) }
        }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()
}

private class MaterialMaskView(context: Context) : ImageView(context) {
    private var lastDark: Boolean? = null
    override fun onAttachedToWindow() { super.onAttachedToWindow(); applyMaterial(lastDark ?: false, force = true) }
    fun applyMaterial(dark: Boolean, force: Boolean = false) {
        if (!force && lastDark == dark) return
        lastDark = dark
        val colors = if (dark) intArrayOf(0xE6A1A1A1.toInt(), 0x4DE6E6E6, 0xFF1AF500.toInt())
            else intArrayOf(0xCC4A4A4A.toInt(), 0xFF4F4F4F.toInt(), 0xFF1AF200.toInt())
        val modes = intArrayOf(if (dark) 18 else 19, 100, 106)
        val applied = runCatching {
            View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType).invoke(this, 3)
            runCatching { View::class.java.getMethod("clearMiBackgroundBlendColor").invoke(this) }
            val add = View::class.java.getMethod("addMiBackgroundBlendColor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            colors.indices.forEach { add.invoke(this, colors[it], modes[it]) }
        }.isSuccess
        backgroundTintList = if (applied) null else ColorStateList.valueOf(
            if (dark) Color.argb(217, 185, 180, 200) else Color.argb(204, 74, 74, 74))
    }
}
