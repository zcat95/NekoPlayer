package com.neko.player

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerTopControls(backdrop: Backdrop, dark: Boolean, accelerated: Boolean,
                               onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (dark) Color.White else Color.Black
    Row(modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top))
        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
        .padding(top = 2.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (accelerated) SoftGlassCircleSurface(backdrop, dark,
            Modifier.size(48.dp).testTag("playback-speed").clearAndSetSemantics {
                contentDescription = "2 倍速播放"
                liveRegion = LiveRegionMode.Polite
            }) {
            Text("2×", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tint)
        } else Spacer(Modifier.size(48.dp))
        SoftGlassCircleButton(onSettings, backdrop, dark, Modifier.size(48.dp),
            buttonModifier = Modifier.testTag("global-settings")) {
            Icon(MiuixIcons.Regular.Settings, contentDescription = "设置", modifier = Modifier.size(24.dp), tint = tint)
        }
    }
}
