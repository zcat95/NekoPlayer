// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
// Adapted from HyperChanger MainActivity.kt (5d84f123): top-bar material and glass back button.
package com.neko.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

private const val TopBarMaterialHeightFactor = 1.4926275f
private const val AlphaMaskShader = """
uniform shader content;
uniform float2 size;
half4 main(float2 coord) {
    float blurAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
    return content.eval(coord) * blurAlpha;
}
"""

/** Fixed 10dp progressive blur, identical to HyperChanger; no user strength setting. */
internal fun Modifier.hyperProgressiveBlur(backdrop: Backdrop): Modifier {
    val runtime = isRuntimeShaderSupported()
    val mask = if (runtime) Modifier else Modifier
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            // Android 12 fallback for the same smoothstep alpha mask, applied to the blur output.
            val stops = (0..16).map { index ->
                val t = index / 16f
                (.5f + t * .5f) to Color.White.copy(alpha = 1f - t * t * (3f - 2f * t))
            }.toTypedArray()
            drawRect(Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
        }
    return then(mask).drawPlainBackdrop(backdrop, shape = { RectangleShape }, effects = {
        blur(10.dp.toPx())
        if (runtime) runtimeShaderEffect("progressive-blur-alpha-mask", AlphaMaskShader, "content") {
            setFloatUniform("size", size.width, size.height)
        }
    })
}

@Composable
internal fun BoxScope.HyperTopBarMaterial(backdrop: Backdrop, progress: Float, surface: Color = MiuixTheme.colorScheme.surface,
                                        onMaterialBackground: Boolean = false) {
    if (!onMaterialBackground) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = 1f - progress }
            .drawPlainBackdrop(backdrop, shape = { RectangleShape }, effects = { blur(6.dp.toPx()) },
                onDrawSurface = { drawRect(surface.copy(alpha = .55f)) }))
    }
    Box(Modifier.matchParentSize().expandDrawHeight(TopBarMaterialHeightFactor)
        .graphicsLayer { alpha = progress }.hyperProgressiveBlur(backdrop))
    if (!onMaterialBackground && progress > 0f) {
        Box(Modifier.matchParentSize().expandDrawHeight(TopBarMaterialHeightFactor)
            .graphicsLayer { alpha = progress }
            .background(Brush.verticalGradient(0f to surface.copy(alpha = .92f),
                .72f to surface.copy(alpha = .18f), 1f to Color.Transparent)))
    }
}

private fun Modifier.expandDrawHeight(factor: Float) = layout { measurable, constraints ->
    val expandedHeight = (constraints.maxHeight * factor).toInt()
    val placeable = measurable.measure(constraints.copy(minHeight = expandedHeight, maxHeight = expandedHeight))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
}

@Composable
internal fun HyperBackButton(backdrop: Backdrop, collapsedFraction: Float, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val drag = remember(scope) {
        DampedDragAnimation(scope, 0f, -1f..1f, .001f, 1f, 1.08f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount -> updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f)) })
    }
    val highlight = remember(scope) {
        InteractiveHighlight(scope) { size, offset -> Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height)) }
    }
    val tint = MiuixTheme.colorScheme.surface.copy(alpha = .80f)
    val color = MiuixTheme.colorScheme.onSurface
    val glassAlpha by animateFloatAsState(collapsedFraction, tween(180), label = "toolbarButtonGlassAlpha")
    Box(Modifier.padding(PaddingValues(start = 8.dp)).size(46.dp)
        .then(highlight.gestureModifier).then(drag.modifier)
        .clickable(interactionSource = null, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = glassAlpha }
            .drawBackdrop(backdrop, shape = { CircleShape },
                effects = {
                    vibrancy(); blur(2.dp.toPx())
                    lens((16.dp + 4.dp * drag.pressProgress).toPx(), (32.dp + 8.dp * drag.pressProgress).toPx(),
                        depthEffect = true, chromaticAberration = true)
                },
                highlight = { Highlight.Default.copy(width = .75.dp, blurRadius = .4.dp, alpha = .76f + drag.pressProgress * .24f) },
                shadow = { Shadow.Default.copy(radius = 5.dp, color = Color.Black, alpha = .24f) }, innerShadow = null,
                layerBlock = {
                    val velocity = abs(drag.velocity.coerceIn(-1f, 1f))
                    scaleX = drag.scaleX * (1f + velocity * .12f)
                    scaleY = drag.scaleY / (1f + velocity * .08f)
                    translationX = drag.value * 5.dp.toPx()
                }, onDrawSurface = { drawCircle(tint) }).then(highlight.modifier))
        Image(MiuixIcons.Regular.ChevronBackward, "返回", Modifier.size(22.dp).graphicsLayer { translationX = -1.15.dp.toPx() },
            colorFilter = ColorFilter.tint(color))
    }
}
