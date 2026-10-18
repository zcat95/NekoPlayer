// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
// Adapted from HyperChanger AppPage, AppList and Group (5d84f123).
package com.neko.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsScaffold(title: String, onBack: (() -> Unit)? = null, showLargeTitle: Boolean = true,
                              pageBackground: (@Composable BoxScope.() -> Unit)? = null,
                              content: @Composable (PaddingValues, ScrollBehavior) -> Unit) {
    val scroll = MiuixScrollBehavior()
    val background = rememberLayerBackdrop()
    val body = rememberLayerBackdrop()
    val backdrop = rememberCombinedBackdrop(background, body)
    val surface = MiuixTheme.colorScheme.surface
    val fraction = scroll.state.collapsedFraction.coerceIn(0f, 1f)
    val materialProgress by animateFloatAsState(fraction, tween(280, easing = FastOutSlowInEasing), label = "topBarMaterialProgress")
    Box(Modifier.fillMaxSize().background(surface)) {
        Box(Modifier.fillMaxSize().layerBackdrop(background)) { pageBackground?.invoke(this) }
        CompositionLocalProvider(LocalSettingsBackdrop provides backdrop) {
            Scaffold(containerColor = Color.Transparent, topBar = {
                Box {
                    HyperTopBarMaterial(backdrop, materialProgress, onMaterialBackground = pageBackground != null)
                    TopAppBar(title = "", largeTitle = if (showLargeTitle) title else "", color = Color.Transparent,
                        scrollBehavior = scroll, navigationIcon = { onBack?.let { HyperBackButton(backdrop, fraction, it) } })
                    Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(52.dp).fillMaxWidth()
                        .graphicsLayer { alpha = fraction }.padding(horizontal = 64.dp), contentAlignment = Alignment.Center) {
                        Text(title, style = MiuixTheme.textStyles.title3, maxLines = 1)
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().layerBackdrop(body)) { content(padding, scroll) }
            }
        }
    }
}

@Composable
internal fun SettingsList(padding: PaddingValues, scroll: ScrollBehavior, bottom: Int = 112, content: LazyListScope.() -> Unit) {
    val direction = LocalLayoutDirection.current
    LazyColumn(Modifier.fillMaxSize().testTag("settings-list").background(MiuixTheme.colorScheme.surface).nestedScroll(scroll.nestedScrollConnection),
        contentPadding = PaddingValues(start = padding.calculateStartPadding(direction) + 16.dp,
            top = padding.calculateTopPadding() + 4.dp, end = padding.calculateEndPadding(direction) + 16.dp,
            bottom = padding.calculateBottomPadding() + bottom.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(title, insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp), content = content)
    }
}
