// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
// HyperOS floating bar ported from HyperChanger GlassNavigation.kt (5d84f123).
package com.neko.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.shapes.Capsule

internal const val HyperFloatingScale = .82f
internal val HyperFloatingControlHeight = 72.dp * HyperFloatingScale
internal val HyperFloatingBottomSpacing = 8.dp * HyperFloatingScale

internal data class FloatingTab(val label: String, val icon: ImageVector)

@Composable
internal fun HyperFloatingNavigationBar(tabs: List<FloatingTab>, selectedIndex: MutableIntState,
                                        backdrop: Backdrop, isDarkTheme: Boolean, onTabSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    val blurRadius = 18f
    val showIcons = true
    val showLabels = true
    val advancedMaterial = isRuntimeShaderSupported()
    val accentColorOverride: Color? = null
    val dragWholeFloatingBar = false
    val harmonyFloating = false
    val enableDrag = true
    val tabIconContent: (@Composable (Int, Color) -> Unit)? = null
    // Keep the OS4 floating bar compact without changing the other navigation styles.
    val floatingScale = HyperFloatingScale
    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    val tabCount = tabs.size.coerceAtLeast(1)
    val initialIndex = selectedIndex.intValue.coerceIn(0, tabCount - 1)
    val dragAnimation = remember(tabCount) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = initialIndex.toFloat(),
            valueRange = 0f..(tabCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // OS4 compresses the floating pill while it is held, then springs back on release.
            pressedScale = 0.9f,
            onDragStopped = {
                val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabCount - 1)
                animateToValue(target.toFloat())
                if (target != selectedIndex.intValue) onTabSelected(target)
            },
            onDrag = { size, amount ->
                val slotWidth = size.width.toFloat() / tabCount.coerceAtLeast(1)
                if (slotWidth > 0f) {
                    updateValue((targetValue + amount.x / slotWidth).fastCoerceIn(0f, (tabCount - 1).toFloat()))
                }
            },
            // OS4 restores the pressed scale as soon as the finger is released.
            awaitTargetBeforeRelease = false,
            followFingerImmediately = dragWholeFloatingBar,
            onTap = if (dragWholeFloatingBar) ({ size, position ->
                val slotWidth = size.width.toFloat() / tabCount
                if (slotWidth > 0f) {
                    val target = (position.x / slotWidth).toInt().fastCoerceIn(0, tabCount - 1)
                    animateToValue(target.toFloat())
                    if (target != selectedIndex.intValue) onTabSelected(target)
                }
            }) else null,
        )
    }
    val touchInteractionSource = remember { MutableInteractionSource() }
    val touchPressed by touchInteractionSource.collectIsPressedAsState()
    val touchProgress by animateFloatAsState(
        targetValue = if (harmonyFloating && touchPressed) 1f else 0f,
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.82f),
        label = "harmonyFloatingTouchProgress",
    )
    val visualPressProgress = if (harmonyFloating) touchProgress else dragAnimation.pressProgress
    val interactiveHighlight = remember(animationScope, tabCount) {
        InteractiveHighlight(animationScope) { size, offset ->
            Offset(
                offset.x.coerceIn(0f, size.width),
                offset.y.coerceIn(0f, size.height),
            )
        }
    }
    LaunchedEffect(selectedIndex.intValue) {
        dragAnimation.animateToValue(selectedIndex.intValue.coerceIn(0, tabCount - 1).toFloat())
    }
    val contentColor = if (isDarkTheme) Color.White else Color.Black
    val selectedContentColor = accentColorOverride ?: contentColor
    val pressedIndex = if (dragAnimation.pressProgress > 0.001f) {
        dragAnimation.value.fastRoundToInt().fastCoerceIn(0, tabCount - 1)
    } else {
        -1
    }
    val selectedSurface = if (isDarkTheme) {
        Color.White.copy(alpha = 0.18f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }
    val containerSurface = if (isDarkTheme) {
        Color.Black.copy(alpha = if (harmonyFloating) 0.36f else 0.30f)
    } else {
        Color(0xFFF5F5F5).copy(alpha = if (harmonyFloating) 0.48f else 0.42f)
    }
    val fallbackSurface = if (isDarkTheme) {
        Color(0xFF242424).copy(alpha = 0.88f)
    } else {
        Color(0xFFF5F5F5).copy(alpha = 0.82f)
    }
    val capsule = Capsule()
    val shadowColor = Color.Black.copy(alpha = 0.35f)

    BoxWithConstraints(
        modifier.height(HyperFloatingControlHeight).testTag("floating-navigation"),
        contentAlignment = Alignment.CenterStart,
    ) {
        val innerPadding = 4.dp * floatingScale
        val innerWidthPx = with(density) { (maxWidth - innerPadding * 2).toPx() }
        val tabWidthPx = innerWidthPx / tabCount
        val tabWidth = with(density) { tabWidthPx.toDp() }
        val selectedTranslation = dragAnimation.value * tabWidthPx
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (harmonyFloating) {
                        // Match the title button glass response: the capsule expands visibly
                        // while the touch highlight and refraction build up.
                        val deformation = lerp(1f, 1.08f, visualPressProgress)
                        scaleX = deformation
                        scaleY = deformation
                    }
                }
                .shadow(
                    elevation = 6.dp,
                    shape = capsule,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
                .then(
                    if (advancedMaterial) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { capsule },
                            effects = {
                                vibrancy()
                                blur(with(density) {
                                    (blurRadius * if (harmonyFloating) 0.29f else 0.24f).dp.toPx()
                                })
                                if (harmonyFloating) {
                                    // Harmony's capsule keeps its liquid refraction at rest. A
                                    // press boosts it instead of being the only time it exists.
                                    val progress = 1f + 0.3f * visualPressProgress
                                    lens(
                                        refractionHeight = 16.dp.toPx() * progress,
                                        refractionAmount = 32.dp.toPx() * progress,
                                        depthEffect = true,
                                        chromaticAberration = false,
                                    )
                                } else {
                                    // Keep the lens pronounced while avoiding an overly strong refraction.
                                    lens(
                                        refractionHeight = 16.dp.toPx(),
                                        refractionAmount = 32.dp.toPx(),
                                        depthEffect = true,
                                        chromaticAberration = false,
                                    )
                                }
                            },
                            highlight = {
                                Highlight.Default.copy(
                                    alpha = if (harmonyFloating) {
                                        // Keep the edge highlight visible when idle, then make
                                        // the pressed state noticeably brighter.
                                        val base = if (isDarkTheme) 0.78f else 0.96f
                                        (base * (0.72f + 0.28f * visualPressProgress))
                                            .coerceIn(0f, 1f)
                                    } else {
                                        if (isDarkTheme) 0.78f else 0.96f
                                    }
                                )
                            },
                            onDrawSurface = {
                                drawRect(containerSurface)
                            },
                        )
                    } else Modifier.clip(capsule).background(fallbackSurface)
                )
        )
        Box(Modifier.fillMaxSize().padding(innerPadding).testTag("floating-navigation-body")) {
            // Draw the selection behind labels, while a transparent overlay below captures drag.
            if (!harmonyFloating) Box(
                Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = selectedTranslation
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                    }
                    .clip(capsule)
                    .background(selectedSurface)
            )
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val pressedProgress = if (pressedIndex == index) dragAnimation.pressProgress else 0f
                    val color = if (harmonyFloating) {
                        if (index == selectedIndex.intValue || pressedProgress > 0f) Color(0xFF0088FF) else contentColor.copy(alpha = 0.58f)
                    } else if (pressedProgress > 0f) {
                        Color.Gray
                    } else {
                        // OS4 uses the hover capsule, rather than a different tint, to show selection.
                        selectedContentColor
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .semantics { selected = index == selectedIndex.intValue }
                            .fillMaxHeight()
                            .graphicsLayer {
                                val scale = lerp(1f, 0.85f, pressedProgress)
                                scaleX = scale
                                scaleY = scale
                            }
                            .clickable(
                                interactionSource = if (harmonyFloating) touchInteractionSource else null,
                                indication = null,
                                role = Role.Tab,
                            ) {
                                onTabSelected(index)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (showIcons) {
                            val icon = tab.icon
                            if (tabIconContent != null) {
                                tabIconContent(index, color)
                            } else {
                                Image(icon, null, Modifier.size(28.dp * floatingScale), colorFilter = ColorFilter.tint(color))
                            }
                        }
                        if (showLabels && tab.label != "+") {
                            BasicText(
                                tab.label,
                                style = TextStyle(
                                    color = color,
                                    fontSize = if (showIcons) 15.sp * floatingScale else 17.sp * floatingScale,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                ),
                                modifier = if (showIcons) Modifier.padding(top = 2.dp * floatingScale) else Modifier,
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = selectedTranslation
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                    }
                    .then(if (dragWholeFloatingBar || !enableDrag) Modifier else dragAnimation.modifier)
            )
            if (dragWholeFloatingBar && enableDrag) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(dragAnimation.modifier)
                )
            }
            // Draw the interactive highlight above the backdrop surface. InteractiveHighlight's
            // draw modifier renders before its child content, so it must be a final overlay here.
            if (harmonyFloating) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(capsule)
                        .then(interactiveHighlight.modifier)
                )
            }
        }
    }
}
