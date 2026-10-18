package com.neko.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerBottomBar(selectedIndex: MutableIntState, backdrop: Backdrop, dark: Boolean,
                             likeEnabled: Boolean, isLiked: Boolean, onLike: () -> Unit, onSelect: (Int) -> Unit,
                             modifier: Modifier = Modifier) {
    val tabs = remember { listOf(FloatingTab("首页", MiuixIcons.Regular.All), FloatingTab("收藏", MiuixIcons.Regular.Favorites)) }
    // Keep the complete two-piece group centred, with stable insets during fullscreen playback.
    BoxWithConstraints(modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))
        .height(100.dp)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)), contentAlignment = Alignment.BottomCenter) {
        val barWidth = (maxWidth * .53f * HyperFloatingScale).coerceIn(160.dp, 280.dp)
        Row(Modifier.padding(bottom = HyperFloatingBottomSpacing).testTag("floating-control-group"),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HyperFloatingNavigationBar(tabs, selectedIndex, backdrop, dark, onSelect, Modifier.width(barWidth))
            FloatingLikeButton(isLiked, onLike, backdrop, dark, Modifier.size(HyperFloatingControlHeight), enabled = likeEnabled)
        }
    }
}

@Composable
private fun FloatingLikeButton(isLiked: Boolean, onClick: () -> Unit, backdrop: Backdrop, dark: Boolean,
                               modifier: Modifier, enabled: Boolean) {
    SoftGlassCircleButton(onClick, backdrop, dark, modifier, enabled,
        buttonModifier = Modifier.testTag("floating-like").semantics {
            selected = isLiked
            stateDescription = if (isLiked) "已点赞" else "未点赞"
        }) {
        Icon(painterResource(if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
            contentDescription = if (isLiked) "取消点赞" else "点赞视频", modifier = Modifier.size(26.dp),
            tint = (if (isLiked) MiuixTheme.colorScheme.primary else if (dark) Color.White else Color.Black)
                .copy(alpha = if (enabled) 1f else .35f))
    }
}

@Composable
internal fun SoftGlassCircleButton(onClick: () -> Unit, backdrop: Backdrop, dark: Boolean, modifier: Modifier,
                                   enabled: Boolean = true, buttonModifier: Modifier = Modifier,
                                   content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val press = remember(scope) {
        DampedDragAnimation(scope, 0f, -1f..1f, .001f, 1f, .9f,
            onDragStopped = { animateToValue(0f) }, onDrag = { _, _ -> })
    }
    Box(modifier.then(if (enabled) press.modifier else Modifier)) {
        SoftGlassCircleSurface(backdrop, dark,
            Modifier.fillMaxSize().graphicsLayer { scaleX = press.scaleX; scaleY = press.scaleY }) {}
        IconButton(onClick, buttonModifier.fillMaxSize().semantics {
            if (!enabled) disabled()
        }, enabled = enabled, backgroundColor = Color.Transparent, minWidth = 48.dp, minHeight = 48.dp) {
            Box(Modifier.graphicsLayer { scaleX = press.scaleX; scaleY = press.scaleY }) { content() }
        }
    }
}

@Composable
internal fun SoftGlassCircleSurface(backdrop: Backdrop, dark: Boolean, modifier: Modifier,
                                    content: @Composable BoxScope.() -> Unit) {
    val surface = if (dark) Color.Black.copy(alpha = .30f) else Color(0xFFF5F5F5).copy(alpha = .42f)
    val material = if (isRuntimeShaderSupported()) {
        Modifier.drawBackdrop(backdrop, shape = { CircleShape },
            effects = {
                vibrancy()
                blur((18.dp * .24f).toPx())
                lens(16.dp.toPx(), 32.dp.toPx(), depthEffect = true, chromaticAberration = false)
            }, highlight = { Highlight.Default.copy(alpha = if (dark) .78f else .96f) },
            onDrawSurface = { drawCircle(surface) })
    } else Modifier.clip(CircleShape).background(
        if (dark) Color(0xFF242424).copy(alpha = .88f) else Color(0xFFF5F5F5).copy(alpha = .82f))
    Box(modifier.shadow(6.dp, CircleShape, clip = false,
        ambientColor = Color.Black.copy(alpha = .35f), spotColor = Color.Black.copy(alpha = .35f))
        .then(material), contentAlignment = Alignment.Center, content = content)
}
