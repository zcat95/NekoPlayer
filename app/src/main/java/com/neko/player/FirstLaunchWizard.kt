// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
// Adapted from HyperChanger's Oobe.kt and toolbar controls (5d84f123).
// Neko Player changes: directory access, playback preferences, safe layouts and lifecycle integration.
package com.neko.player

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private val BrandBlue = Color(0xFF0088FF)
private enum class SetupPage { WELCOME, ACCESS, PREFERENCES, COMPLETE }

/** Animation timings, geometry and glass controls follow HyperChanger's first-launch flow. */
@Composable
internal fun FirstLaunchWizard(
    directoryLabel: String?,
    directoryReadable: Boolean,
    scanning: Boolean,
    settings: PlayerSettings,
    message: String?,
    onChooseDirectory: () -> Unit,
    onSettingsChanged: (PlayerSettings) -> Unit,
    onDismissMessage: () -> Unit,
    onFinished: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SetupPage.WELCOME) }
    var completionVisit by rememberSaveable { mutableIntStateOf(0) }
    var navigatingForward by remember { mutableStateOf(true) }
    var handoff by remember { mutableStateOf<SetupPage?>(null) }
    val background = setupBackground()
    val go: (SetupPage) -> Unit = { next ->
        if (next == SetupPage.COMPLETE && page != next) completionVisit++
        navigatingForward = next.ordinal >= page.ordinal
        handoff = null
        page = next
    }
    val back: () -> Unit = { if (page != SetupPage.WELCOME) go(SetupPage.entries[page.ordinal - 1]) }
    val ready = directoryReadable && !scanning
    val render: @Composable (SetupPage) -> Unit = { destination ->
        when (destination) {
            SetupPage.WELCOME -> WelcomePage { go(SetupPage.ACCESS) }
            SetupPage.ACCESS -> AccessPage(directoryLabel, ready, scanning, message, back,
                onChooseDirectory, onDismissMessage) { if (ready) go(SetupPage.PREFERENCES) }
            SetupPage.PREFERENCES -> PreferencesPage(settings, onSettingsChanged, back) { go(SetupPage.COMPLETE) }
            SetupPage.COMPLETE -> CompletePage(completionVisit, ready, back) {
                if (ready) onFinished() else go(SetupPage.ACCESS)
            }
        }
    }
    val progress = remember { Animatable(0f) }
    val previewBlur = remember { Animatable(14f) }
    var preview by remember { mutableStateOf<SetupPage?>(null) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var gestureActive by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = page != SetupPage.WELCOME) { events ->
        preview = SetupPage.entries[page.ordinal - 1]
        gestureActive = true
        try {
            events.collect { event ->
                swipeEdge = event.swipeEdge
                progress.snapTo(event.progress.coerceIn(0f, .8f))
                previewBlur.snapTo(14f - 10f * (progress.value / .8f).coerceIn(0f, 1f))
            }
            coroutineScope {
                launch { progress.animateTo(1f, tween(((1f - progress.value) * 240).toInt().coerceAtLeast(1), easing = FastOutSlowInEasing)) }
                launch { previewBlur.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) }
            }
            val target = preview
            back()
            handoff = target
            withFrameNanos { }
        } catch (_: CancellationException) {
            withContext(NonCancellable) { progress.animateTo(0f, tween(180, easing = FastOutSlowInEasing)) }
        } finally {
            gestureActive = false
            preview = null
            withContext(NonCancellable) { progress.snapTo(0f); previewBlur.snapTo(14f) }
        }
    }
    Box(Modifier.fillMaxSize().background(background).testTag("first-launch")) {
        val direction = if (swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
        if (gestureActive) preview?.let { destination ->
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationX = -direction * size.width * (1f - progress.value) / 7f
                scaleX = .975f + .025f * progress.value
                scaleY = scaleX
                alpha = progress.value
            }.blur(previewBlur.value.dp)) { render(destination) }
        }
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = direction * size.width * progress.value / 3f
            scaleX = 1f - .025f * progress.value
            scaleY = scaleX
            alpha = 1f - progress.value
        }) {
            AnimatedContent(page, modifier = Modifier.fillMaxSize(), label = "setupNavigation",
                transitionSpec = {
                    if (handoff == targetState) EnterTransition.None togetherWith ExitTransition.None
                    else setupTransition(targetState.ordinal >= initialState.ordinal)
                }) { destination ->
                val radius by transition.animateFloat(
                    transitionSpec = { tween(520, easing = FastOutSlowInEasing) }, label = "setupBlur") { state ->
                    if (navigatingForward) { if (state == EnterExitState.PostExit) 12f else 0f }
                    else { if (state == EnterExitState.PreEnter) 12f else 0f }
                }
                Box(Modifier.fillMaxSize().blur(if (destination == handoff) 0.dp else radius.dp)) { render(destination) }
            }
        }
    }
}

internal fun setupTransition(forward: Boolean = true): ContentTransform = if (forward) {
    (slideInHorizontally(tween(560, easing = FastOutSlowInEasing)) { it / 7 } + fadeIn(tween(420)) +
        scaleIn(tween(560, easing = FastOutSlowInEasing), .985f)) togetherWith
        (slideOutHorizontally(tween(460, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(360)) +
            scaleOut(tween(460, easing = FastOutSlowInEasing), .975f))
} else {
    (slideInHorizontally(tween(520, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(380)) +
        scaleIn(tween(520, easing = FastOutSlowInEasing), .975f)) togetherWith
        (slideOutHorizontally(tween(420, easing = FastOutSlowInEasing)) { it / 7 } + fadeOut(tween(320)) +
            scaleOut(tween(420, easing = FastOutSlowInEasing), .985f))
}

@Composable
private fun setupBackground() = if (MiuixTheme.colorScheme.background.luminance() < .5f) Color.Black else MiuixTheme.colorScheme.surface

/** Keeps the original portrait spacing while allowing landscape and large text to scroll. */
@Composable
private fun WeightedSetupBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val minimumHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minimumHeight), content = content)
    }
}

@Composable
private fun WelcomePage(next: () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val scope = rememberCoroutineScope()
    val drag = remember(scope) {
        DampedDragAnimation(scope, 0f, -1f..1f, .001f, 1f, 1.08f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount -> updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f)) })
    }
    val highlight = remember(scope) {
        InteractiveHighlight(scope) { size, offset -> Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height)) }
    }
    Box(Modifier.fillMaxSize().background(setupBackground())) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 28.dp)) {
            Spacer(Modifier.height(76.dp))
            WeightedSetupBody(Modifier.weight(1f)) {
                Spacer(Modifier.weight(.37f))
                Text("欢迎使用", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("Neko Player", color = BrandBlue, fontSize = 39.sp, fontWeight = FontWeight.Bold)
                Text("一款简洁的\n本地短视频\n播放器", Modifier.padding(top = 25.dp),
                    fontSize = 27.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.padding(top = 40.dp).size(65.dp)
                    .then(highlight.gestureModifier).then(drag.modifier).clip(CircleShape)
                    .drawBackdrop(backdrop = backdrop, shape = { CircleShape },
                        effects = { vibrancy(); blur(2.dp.toPx()); setupGlassLens(drag.pressProgress) },
                        highlight = { setupGlassHighlight(drag.pressProgress) },
                        shadow = { Shadow.Default.copy(radius = 5.dp, color = Color.Black, alpha = .24f) },
                        innerShadow = null,
                        layerBlock = {
                            val velocity = abs(drag.velocity.coerceIn(-1f, 1f))
                            scaleX = drag.scaleX * (1f + velocity * .12f)
                            scaleY = drag.scaleY / (1f + velocity * .08f)
                            translationX = drag.value * 5.dp.toPx()
                        },
                        onDrawSurface = { drawCircle(BrandBlue.copy(alpha = .92f)) })
                    .then(highlight.modifier)
                    .clickable(interactionSource = null, indication = null, onClick = next), contentAlignment = Alignment.Center) {
                    Image(MiuixIcons.Regular.ChevronForward, "继续", Modifier.size(34.dp), colorFilter = ColorFilter.tint(Color.White))
                }
                Spacer(Modifier.weight(.63f))
            }
        }
    }
}

/** HyperChanger's compact top bar, card list and floating 58dp pill. */
@Composable
private fun SetupFrame(back: () -> Unit, buttonText: String, enabled: Boolean = true, next: () -> Unit,
                       content: @Composable (PaddingValues) -> Unit) {
    val backdrop = rememberLayerBackdrop()
    Scaffold(containerColor = setupBackground(),
        topBar = {
            SmallTopAppBar(title = "", color = Color.Transparent, navigationIcon = {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = back), contentAlignment = Alignment.Center) {
                    Image(MiuixIcons.Regular.ChevronBackward, "返回", Modifier.size(27.dp),
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface))
                }
            })
        },
        floatingToolbar = {
            SetupButton(buttonText, enabled, next, backdrop,
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(58.dp))
        }) { padding ->
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { content(padding) }
    }
}

@Composable
private fun SetupList(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    val direction = LocalLayoutDirection.current
    LazyColumn(Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = padding.calculateStartPadding(direction) + 16.dp,
            end = padding.calculateEndPadding(direction) + 16.dp,
            top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, Modifier.fillMaxWidth().padding(top = 52.dp, bottom = 42.dp), textAlign = TextAlign.Center,
        fontSize = 32.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun AccessPage(directory: String?, ready: Boolean, scanning: Boolean, message: String?, back: () -> Unit,
                       chooseDirectory: () -> Unit, dismissMessage: () -> Unit, next: () -> Unit) {
    SetupFrame(back, "继续", ready, next) { padding ->
        SetupList(padding) {
            item { SectionTitle("给予权限") }
            item { StatusCard("视频目录读取权限", if (ready) "已获得持久读取权限" else "请选择视频目录并允许读取", ready) }
            item { StatusCard("视频目录", directory ?: "尚未选择路径", ready) }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    TextButton(if (directory == null) "授权并选择目录" else "更改路径", chooseDirectory,
                        Modifier.fillMaxWidth().heightIn(min = 64.dp))
                }
            }
            item {
                Text(if (scanning) "正在读取目录…" else "选择存放视频的文件夹，在系统页面点击“使用此文件夹”并允许访问。",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (message != null) item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
                    Text(message, color = MiuixTheme.colorScheme.error)
                    TextButton("知道了", dismissMessage)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, summary: String, ready: Boolean) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                Text(summary, Modifier.padding(top = 3.dp), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (ready) Image(MiuixIcons.Basic.Check, null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(BrandBlue))
        }
    }
}

@Composable
private fun PreferencesPage(settings: PlayerSettings, update: (PlayerSettings) -> Unit, back: () -> Unit, next: () -> Unit) {
    SetupFrame(back, "继续", next = next) { padding ->
        SetupList(padding) {
            item { SectionTitle("播放偏好") }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(settings.autoNext, { update(settings.copy(autoNext = it)) },
                        "自动播放下一个", summary = "播完接续下一个，关闭则单视频循环")
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(settings.shuffle, { update(settings.copy(shuffle = it)) },
                        "随机播放", summary = "打乱列表播放，关闭则按名称顺序")
                }
            }
        }
    }
}

@Composable
private fun CompletePage(confettiKey: Int, ready: Boolean, back: () -> Unit, finish: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        SetupFrame(back, if (ready) "开始使用" else "重新选择目录", next = finish) { padding ->
            WeightedSetupBody(Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp)) {
                Spacer(Modifier.weight(.32f))
                Text("设置完成！", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(54.dp))
                Text("欢迎使用", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("Neko Player", color = BrandBlue, fontSize = 39.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(.68f))
            }
        }
        CompletionConfetti(confettiKey, Modifier.fillMaxSize())
    }
}

private fun BackdropEffectScope.setupGlassLens(progress: Float) {
    val p = progress.coerceIn(0f, 1f)
    lens((16.dp + 4.dp * p).toPx(), (32.dp + 8.dp * p).toPx(), depthEffect = true, chromaticAberration = true)
}

private fun setupGlassHighlight(progress: Float) = Highlight.Default.copy(width = .75.dp, blurRadius = .4.dp, alpha = .76f + progress.coerceIn(0f, 1f) * .24f)

@Composable
private fun SetupButton(text: String, enabled: Boolean, click: () -> Unit, backdrop: LayerBackdrop, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val drag = remember(scope) {
        DampedDragAnimation(scope, 0f, -1f..1f, .001f, 1f, 1.035f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount -> updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f)) })
    }
    val highlight = remember(scope) {
        InteractiveHighlight(scope) { size, offset -> Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height)) }
    }
    val shape = RoundedCornerShape(50.dp)
    Box(modifier.then(if (enabled) highlight.gestureModifier.then(drag.modifier) else Modifier)
        .clip(shape)
        .drawBackdrop(backdrop = backdrop, shape = { shape },
            effects = { vibrancy(); blur(2.dp.toPx()); setupGlassLens(drag.pressProgress) },
            highlight = { setupGlassHighlight(drag.pressProgress) },
            shadow = { Shadow.Default.copy(radius = 5.dp, color = Color.Black, alpha = .24f) }, innerShadow = null,
            onDrawSurface = { drawRoundRect(BrandBlue.copy(alpha = .92f)) })
        .graphicsLayer { alpha = if (enabled) 1f else .42f; scaleX = drag.scaleX; scaleY = drag.scaleY; translationX = drag.value * 4.dp.toPx() }
        .then(if (enabled) highlight.modifier else Modifier)
        .clickable(enabled = enabled, interactionSource = null, indication = null, onClick = click), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
    }
}

private data class ConfettiPiece(
    val xFraction: Float,
    val delay: Float,
    val drift: Float,
    val frequency: Float,
    val phase: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val rotationTurns: Float,
    val color: Color,
    val round: Boolean,
    val restingProgress: Float?,
)

@Composable
internal fun CompletionConfetti(
    playKey: Int,
    modifier: Modifier = Modifier,
    pieceCount: Int = 54,
    restingCountRange: IntRange = 1..3,
    durationMillis: Int = 3_800,
) {
    val progress = remember { Animatable(0f) }
    val pieces = remember(playKey) {
        val random = Random(System.nanoTime())
        val count = pieceCount.coerceAtLeast(1)
        val restingCount = random.nextInt(
            restingCountRange.first.coerceAtLeast(0),
            restingCountRange.last.coerceAtLeast(restingCountRange.first) + 1,
        ).coerceAtMost(count)
        val palette = listOf(
            Color(0xFFFF3B30),
            Color(0xFFFFCC00),
            Color(0xFF34C759),
            Color(0xFF00A7FF),
            Color(0xFF5856D6),
            Color(0xFFFF2D8D),
        )
        List(count) { index ->
            ConfettiPiece(
                xFraction = random.nextFloat(),
                delay = random.nextFloat() * .3f,
                drift = 18f + random.nextFloat() * 54f,
                frequency = 1.5f + random.nextFloat() * 2.5f,
                phase = random.nextFloat() * 6.28318f,
                width = 5f + random.nextFloat() * 6f,
                height = 9f + random.nextFloat() * 9f,
                rotation = random.nextFloat() * 360f,
                rotationTurns = 1.5f + random.nextFloat() * 3.5f,
                color = palette[random.nextInt(palette.size)],
                round = random.nextInt(5) == 0,
                restingProgress = if (index < restingCount) .52f + random.nextFloat() * .2f else null,
            )
        }
    }
    LaunchedEffect(playKey) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis, easing = LinearEasing))
    }
    Canvas(modifier) {
        val timeline = progress.value
        val overlayAlpha = when {
            timeline < .08f -> timeline / .08f
            timeline > .82f -> ((1f - timeline) / .18f).coerceIn(0f, 1f)
            else -> 1f
        }
        pieces.forEach { piece ->
            val localProgress = ((timeline - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (localProgress <= 0f || overlayAlpha <= 0f) return@forEach
            val fallProgress = piece.restingProgress?.let { localProgress.coerceAtMost(it) } ?: localProgress
            val x = size.width * piece.xFraction +
                sin(fallProgress * piece.frequency * 6.28318f + piece.phase) * piece.drift.dp.toPx()
            val pieceHeight = piece.height.dp.toPx()
            val y = -pieceHeight + (size.height + pieceHeight * 2f) * fallProgress
            val alpha = overlayAlpha * (localProgress * 9f).coerceAtMost(1f)
            rotate(
                degrees = piece.rotation + localProgress * 360f * piece.rotationTurns,
                pivot = Offset(x, y),
            ) {
                if (piece.round) {
                    drawCircle(piece.color.copy(alpha = alpha), radius = piece.width.dp.toPx() * .58f, center = Offset(x, y))
                } else {
                    val width = piece.width.dp.toPx()
                    drawRect(
                        color = piece.color.copy(alpha = alpha),
                        topLeft = Offset(x - width / 2f, y - pieceHeight / 2f),
                        size = Size(width, pieceHeight),
                    )
                }
            }
        }
    }
}
