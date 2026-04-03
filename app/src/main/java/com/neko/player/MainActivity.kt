package com.neko.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SliderDefaults.sliderColors
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

object SettingsManager {
    // Runtime memory state for cold-mute tracking logic
    var globalMuteState = mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Absolute full-screen immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            MiuixTheme {
                Scaffold {
                    AppScreen()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Intercept volume keys to immediately disable mute, while keeping system behavior intact
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            SettingsManager.globalMuteState.value = false
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun AppScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("neko_prefs", android.content.Context.MODE_PRIVATE)

    var selectedUri by remember {
        val savedUri = prefs.getString("saved_directory_uri", null)
        mutableStateOf<Uri?>(savedUri?.let { Uri.parse(it) })
    }
    var videoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    
    var isSettingsOpen by remember { mutableStateOf(false) }

    // SharedPreferences states
    var likedUris by remember {
        mutableStateOf(prefs.getStringSet("liked_video_uris", emptySet())?.toSet() ?: emptySet())
    }
    var isShuffleEnabled by remember { mutableStateOf(prefs.getBoolean("isShuffleEnabled", true)) }
    var isAutoNext by remember { mutableStateOf(prefs.getBoolean("isAutoNext", true)) }
    var resizeMode by remember { mutableStateOf(prefs.getInt("resizeMode", AspectRatioFrameLayout.RESIZE_MODE_ZOOM)) }
    var isGlobal2xSpeed by remember { mutableStateOf(prefs.getBoolean("isGlobal2xSpeed", false)) }
    var isBackgroundPlayAllowed by remember { mutableStateOf(prefs.getBoolean("isBackgroundPlayAllowed", false)) }
    var isColdStartMuted by remember { mutableStateOf(prefs.getBoolean("isColdStartMuted", false)) }

    // 展示特效设置（实时响应，持久化）
    var blurRadius by remember { mutableStateOf(prefs.getFloat("blurRadius", 120f)) }
    var maskHeightDp by remember { mutableStateOf(prefs.getFloat("maskHeightDp", 56f)) }
    var headerHPad by remember { mutableStateOf(prefs.getFloat("headerHPad", 30f)) }  // 标签水平边距
    var headerVPad by remember { mutableStateOf(prefs.getFloat("headerVPad", 12f)) }  // 标签垂直边距

    // Init global mute once on mount
    LaunchedEffect(Unit) {
        SettingsManager.globalMuteState.value = isColdStartMuted
    }

    val dirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            prefs.edit().putString("saved_directory_uri", uri.toString()).apply()
            selectedUri = uri
            isSettingsOpen = false // Automatically close settings when folder is picked
        }
    }

    LaunchedEffect(selectedUri, isShuffleEnabled) {
        selectedUri?.let { uri ->
            isScanning = true
            withContext(Dispatchers.IO) {
                val newVideoUris = mutableListOf<Uri>()
                try {
                    val documentFile = DocumentFile.fromTreeUri(context, uri)
                    documentFile?.listFiles()?.forEach { file ->
                        val mimeType = file.type ?: ""
                        if (mimeType.startsWith("video/") || file.name?.endsWith(".mp4", ignoreCase = true) == true || file.name?.endsWith(".mkv", ignoreCase = true) == true) {
                            newVideoUris.add(file.uri)
                        }
                    }
                    if (isShuffleEnabled) {
                        newVideoUris.shuffle()
                    } else {
                        newVideoUris.sortBy { it.lastPathSegment }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                withContext(Dispatchers.Main) {
                    videoUris = newVideoUris
                    isScanning = false
                }
            }
        }
    }

    val layerBackdrop = rememberLayerBackdrop()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (selectedUri == null) {
            // Initial Screen
            Button(
                onClick = { dirLauncher.launch(null) },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            ) {
                Text("SELECT VIDEO FOLDER")
            }
        } else if (isScanning) {
            Text(
                text = "Scanning videos...",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (videoUris.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "No videos found. Or folder permission lost.",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
                Button(
                    onClick = { dirLauncher.launch(null) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                ) {
                    Text("Select Another Folder")
                }
            }
        } else {
            // Mode Switch (Horizontal Pager)
            val horizontalPagerState = rememberPagerState(pageCount = { 2 })

            var allModeList by remember { mutableStateOf(videoUris) }
            var likedModeList by remember { mutableStateOf(videoUris.filter { it.toString() in likedUris }) }
            
            var isAnyLongPressActive by remember { mutableStateOf(false) }

            // ─── allModeList ─── 与 likedUris 完全隔离，点赞不会触发此 effect ───────
            LaunchedEffect(isShuffleEnabled, videoUris) {
                allModeList = if (isShuffleEnabled)
                    videoUris.shuffled()
                else
                    videoUris.sortedBy { it.lastPathSegment }
            }

            // ─── likedModeList ─── 只有这里才依赖 likedUris ─────────────────────────
            LaunchedEffect(isShuffleEnabled, videoUris, likedUris) {
                val filtered = videoUris.filter { it.toString() in likedUris }
                likedModeList = if (isShuffleEnabled)
                    filtered.shuffled()
                else
                    filtered.sortedBy { it.lastPathSegment }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(layerBackdrop)  // 只捕捉视频层
            ) {
                HorizontalPager(
                    state = horizontalPagerState,
                    modifier = Modifier.fillMaxSize()
                ) { horizontalPage ->
                    val pageOffset = (horizontalPagerState.currentPage - horizontalPage) + horizontalPagerState.currentPageOffsetFraction
                    val fadeAlpha = 1f - pageOffset.absoluteValue.coerceIn(0f, 1f)
                    
                    Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fadeAlpha }) {
                        val currentList = if (horizontalPage == 0) allModeList else likedModeList

                    if (currentList.isEmpty() && horizontalPage == 1) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No liked videos yet. Swipe left to go back.", color = Color.White)
                        }
                    } else if (currentList.isNotEmpty()) {
                        val savedKey = if (horizontalPage == 0) "saved_video_uri" else "saved_liked_video_uri"
                        val savedVideoUriStr = prefs.getString(savedKey, null)

                        val scope = rememberCoroutineScope()

                        androidx.compose.runtime.key(currentList) {
                            val initialIndex = remember(currentList) {
                                val index = currentList.indexOfFirst { it.toString() == savedVideoUriStr }
                                if (index != -1) index else 0
                            }
                            val vPagerState = rememberPagerState(initialPage = initialIndex, pageCount = { currentList.size })

                            LaunchedEffect(vPagerState.currentPage) {
                                if (currentList.isNotEmpty()) {
                                    prefs.edit().putString(savedKey, currentList[vPagerState.currentPage].toString()).apply()
                                }
                            }

                            VerticalPager(
                                state = vPagerState,
                                modifier = Modifier.fillMaxSize()
                            ) { page ->
                                val videoUri = currentList[page]
                                VideoPlayerPage(
                                    uri = videoUri,
                                    isVisible = vPagerState.currentPage == page && horizontalPagerState.currentPage == horizontalPage,
                                    isLiked = videoUri.toString() in likedUris,
                                    onToggleLike = { liked ->
                                        val newSet = if (liked) likedUris + videoUri.toString() else likedUris - videoUri.toString()
                                        likedUris = newSet
                                        prefs.edit().putStringSet("liked_video_uris", newSet).apply()
                                    },
                                    onOpenSettings = { isSettingsOpen = true },
                                    isAutoNext = isAutoNext,
                                    isGlobal2xSpeed = isGlobal2xSpeed,
                                    isBackgroundPlayAllowed = isBackgroundPlayAllowed,
                                    isMuted = SettingsManager.globalMuteState.value,
                                    resizeMode = resizeMode,
                                    onToggleResizeMode = {
                                        val newMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        } else {
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        }
                                        resizeMode = newMode
                                        prefs.edit().putInt("resizeMode", newMode).apply()
                                    },
                                    onVideoEnded = {
                                        if (page + 1 < currentList.size) {
                                            scope.launch {
                                                vPagerState.animateScrollToPage(page + 1)
                                            }
                                        }
                                    },
                                    onLongPressStateChanged = {
                                        isAnyLongPressActive = it
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    } // <- Close the fadeBox
                }
            }  // ← 关闭 layerBackdrop 捕捉层（只含视频）

            // ── 层 1：毛玻璃模糊背景（空内容，只做视觉效果）──────────────────────────
            // 高度靠内容撑开，必须比文字层稍高以覆盖状态栏区域
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(maskHeightDp.dp)  // 用户可调节，默认 56dp
                    .textureBlur(
                        backdrop = layerBackdrop,
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        blurRadius = blurRadius,  // 用户可调节，默认 120f
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = Color.Black.copy(alpha = 0.12f))
                            )
                        )
                    )
                    .clipToBounds()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        // 下边缘渐变消隐，消除硬边界；文字不在此层故不受 DstOut 影响
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.55f to Color.Transparent,   // 上 55%：保留模糊
                                1.00f to Color.White           // 下 45%：淡出为透明
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstOut
                        )
                    }
            ) { /* 模糊层无内容 */ }

            // ── 层 2：文字标签（在模糊层之上，清晰锐利，无任何 blur）────────────────
            val headerText = if (horizontalPagerState.currentPage == 0) "全部视频" else "已收藏视频"
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(
                        start = headerHPad.dp,
                        end = headerHPad.dp,
                        top = headerVPad.dp,
                        bottom = 8.dp
                    )
            ) {
                Text(
                    text = headerText,
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.CenterStart).pixelShift(2f)
                )

                if (isGlobal2xSpeed || isAnyLongPressActive) {
                    Text(
                        text = "▶▶ 2.0x",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)  // 与左侧标签同行对齐
                            .pixelShift(2f)
                    )
                }
            }
        }


        // Settings Panel — 面板高度 75%，顶部 25% 保持可见以实时预览标签位置
        if (isSettingsOpen) {
            OverlayBottomSheet(
                show = isSettingsOpen,
                title = "设置",
                onDismissRequest = { isSettingsOpen = false },
                // 限制面板高度，留出顶部 25% 观察模糊效果和标签位置
                modifier = Modifier.fillMaxHeight(0.75f),
                // 关闭背景遮暗，让背后的视频和顶部 UI 保持清晰可见
                enableWindowDim = false
            ) {
                SettingsPanelContent(
                    videoCount = videoUris.size,
                    likedCount = likedUris.size,
                    onChangeFolder = { dirLauncher.launch(null) },
                    isShuffleEnabled = isShuffleEnabled,
                    onToggleShuffle = {
                        isShuffleEnabled = it
                        prefs.edit().putBoolean("isShuffleEnabled", it).apply()
                    },
                    isAutoNext = isAutoNext,
                    onToggleAutoNext = {
                        isAutoNext = it
                        prefs.edit().putBoolean("isAutoNext", it).apply()
                    },
                    resizeMode = resizeMode,
                    onToggleResizeMode = {
                        val newMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        resizeMode = newMode
                        prefs.edit().putInt("resizeMode", newMode).apply()
                    },
                    isGlobal2xSpeed = isGlobal2xSpeed,
                    onToggleGlobal2Speed = {
                        isGlobal2xSpeed = it
                        prefs.edit().putBoolean("isGlobal2xSpeed", it).apply()
                    },
                    isBackgroundPlayAllowed = isBackgroundPlayAllowed,
                    onToggleBackgroundPlay = {
                        isBackgroundPlayAllowed = it
                        prefs.edit().putBoolean("isBackgroundPlayAllowed", it).apply()
                    },
                    isColdStartMuted = isColdStartMuted,
                    onToggleColdStartMuted = {
                        isColdStartMuted = it
                        prefs.edit().putBoolean("isColdStartMuted", it).apply()
                        if (it) SettingsManager.globalMuteState.value = true
                    },
                    blurRadius = blurRadius,
                    onBlurRadiusChange = {
                        blurRadius = it
                        prefs.edit().putFloat("blurRadius", it).apply()
                    },
                    maskHeightDp = maskHeightDp,
                    onMaskHeightChange = {
                        maskHeightDp = it
                        prefs.edit().putFloat("maskHeightDp", it).apply()
                    },
                    headerHPad = headerHPad,
                    onHeaderHPadChange = {
                        headerHPad = it
                        prefs.edit().putFloat("headerHPad", it).apply()
                    },
                    headerVPad = headerVPad,
                    onHeaderVPadChange = {
                        headerVPad = it
                        prefs.edit().putFloat("headerVPad", it).apply()
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsPanelContent(
    videoCount: Int,
    likedCount: Int,
    onChangeFolder: () -> Unit,
    isShuffleEnabled: Boolean,
    onToggleShuffle: (Boolean) -> Unit,
    isAutoNext: Boolean,
    onToggleAutoNext: (Boolean) -> Unit,
    resizeMode: Int,
    onToggleResizeMode: (Boolean) -> Unit,
    isGlobal2xSpeed: Boolean,
    onToggleGlobal2Speed: (Boolean) -> Unit,
    isBackgroundPlayAllowed: Boolean,
    onToggleBackgroundPlay: (Boolean) -> Unit,
    isColdStartMuted: Boolean,
    onToggleColdStartMuted: (Boolean) -> Unit,
    blurRadius: Float,
    onBlurRadiusChange: (Float) -> Unit,
    maskHeightDp: Float,
    onMaskHeightChange: (Float) -> Unit,
    headerHPad: Float,
    onHeaderHPadChange: (Float) -> Unit,
    headerVPad: Float,
    onHeaderVPadChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // Data & Storage
        SmallTitle(text = "数据与存储")
        Card {
            BasicComponent(
                title = "已扫描视频",
                summary = "共 $videoCount 个视频"
            )
            BasicComponent(
                title = "已收藏视频",
                summary = "共 $likedCount 个视频"
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            text = "重新选择扫描目录",
            onClick = onChangeFolder,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 4.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Playback Prefs
        SmallTitle(text = "播放偏好")
        Card {
            SwitchPreference(
                title = "随机播放",
                summary = "打乱列表播放，关闭则按名称顺序",
                checked = isShuffleEnabled,
                onCheckedChange = onToggleShuffle
            )
            SwitchPreference(
                title = "自动播放下一个",
                summary = "播完自动滑到下一集，关闭则单曲循环",
                checked = isAutoNext,
                onCheckedChange = onToggleAutoNext
            )
            val resizeText = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "全屏裁切填充 (Crop)" else "原比例显示 (Fit)"
            SwitchPreference(
                title = "画面全屏裁切",
                summary = resizeText,
                checked = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                onCheckedChange = onToggleResizeMode
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Interaction & System
        SmallTitle(text = "交互与系统")
        Card {
            SwitchPreference(
                title = "全局 2.0x 倍速",
                summary = "全局默认强制2倍速，关闭则支持长按触发",
                checked = isGlobal2xSpeed,
                onCheckedChange = onToggleGlobal2Speed
            )
            SwitchPreference(
                title = "后台播放",
                summary = "切到后台不暂停，继续出声",
                checked = isBackgroundPlayAllowed,
                onCheckedChange = onToggleBackgroundPlay
            )
            SwitchPreference(
                title = "启动自动静音",
                summary = "进入应用默认静音，按下系统音量键即解除",
                checked = isColdStartMuted,
                onCheckedChange = onToggleColdStartMuted
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Visual Effects — 四个滑块实时调节
        SmallTitle(text = "视觉效果")
        Card {
            SliderRow("模糊强度", blurRadius, onBlurRadiusChange, 0f..150f, "px")
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(horizontal = 28.dp),
                thickness = 0.5.dp,
                color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.2f)
            )
            SliderRow("遮罩高度", maskHeightDp, onMaskHeightChange, 40f..120f, "dp")
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(horizontal = 28.dp),
                thickness = 0.5.dp,
                color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.2f)
            )
            SliderRow("标签水平边距", headerHPad, onHeaderHPadChange, 16f..80f, "dp")
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(horizontal = 28.dp),
                thickness = 0.5.dp,
                color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.2f)
            )
            SliderRow("标签垂直边距", headerVPad, onHeaderVPadChange, 8f..60f, "dp")
        }
        Spacer(modifier = Modifier.height(32.dp))

        // About
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Neko Player", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("一款极简的本地短视频播放器", fontSize = 14.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(4.dp))
            Text("v2.2.0", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

/**
 * 滑块设置行 — 使用 BasicComponent 官方 API，对齐与 SwitchPreference 完全一致：
 *  • title        → 左侧标题
 *  • endActions   → 右侧数值（与 Switch 开关位置对齐）
 *  • bottomAction → Slider（底部独占一行，insideMargin 自动处理边距）
 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String
) {
    val hyperBlue = androidx.compose.ui.graphics.Color(0xFF2979FF)

    BasicComponent(
        title = label,
        endActions = {
            Text(
                text = "${value.toInt()} $unit",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = hyperBlue
            )
        },
        bottomAction = {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
                colors = sliderColors(
                    foregroundColor = hyperBlue,
                    backgroundColor = hyperBlue.copy(alpha = 0.15f)
                )
            )
        }
    )
}