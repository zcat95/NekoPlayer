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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
            MaterialTheme {
                AppScreen()
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

@OptIn(ExperimentalMaterial3Api::class)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (selectedUri == null) {
            // Initial Screen
            Button(
                onClick = { dirLauncher.launch(null) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E), contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            ) {
                Text(
                    text = "SELECT VIDEO FOLDER",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
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

            // Recreate datasets properly parameterized on user logic
            LaunchedEffect(horizontalPagerState.settledPage, isShuffleEnabled, videoUris, likedUris) {
                if (horizontalPagerState.settledPage == 0) {
                    allModeList = if (isShuffleEnabled) videoUris.shuffled() else videoUris.sortedBy { it.lastPathSegment }
                } else {
                    val filtered = videoUris.filter { it.toString() in likedUris }
                    likedModeList = if (isShuffleEnabled) filtered.shuffled() else filtered.sortedBy { it.lastPathSegment }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = horizontalPagerState,
                    modifier = Modifier.fillMaxSize()
                ) { horizontalPage ->
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
                }
                
                // Status Indicator Overlay
                val headerText = if (horizontalPagerState.currentPage == 0) "全部视频" else "已收藏视频"
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    Text(
                        text = headerText,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    
                    if (isGlobal2xSpeed || isAnyLongPressActive) {
                        Text(
                            text = "▶▶ 2.0x",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                }
            }
        }
        
        // Settings Panel Base Overlay
        if (isSettingsOpen) {
            ModalBottomSheet(onDismissRequest = { isSettingsOpen = false }) {
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
                        if (it) SettingsManager.globalMuteState.value = true // Sync live dynamically
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
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        item {
            Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 24.dp))
        }

        item {
            Text("数据与存储", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("已扫描视频总数: $videoCount\n已收藏视频总数: $likedCount", color = Color.Gray, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onChangeFolder, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("重新选择扫描目录")
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            Text("播放偏好", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            SwitchPreference("随机播放", "打乱列表播放 (关闭则按名称顺序)", isShuffleEnabled, onToggleShuffle)
            SwitchPreference("自动播放下一个", "播完自动滑到下一集 (关闭则单曲循环)", isAutoNext, onToggleAutoNext)
            val resizeText = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "全屏裁切填充 (Crop)" else "原比例显示 (Fit)"
            SwitchPreference("画面比例", resizeText, resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM, onToggleResizeMode)
            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            Text("交互与系统", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            SwitchPreference("全局 2.0x 倍速", "全局默认强制2倍速 (关闭则支持长按触发)", isGlobal2xSpeed, onToggleGlobal2Speed)
            SwitchPreference("后台播放", "切到后台不暂停继续出声", isBackgroundPlayAllowed, onToggleBackgroundPlay)
            SwitchPreference("启动自动静音", "进入应用默认静音。按下系统音量键即解除", isColdStartMuted, onToggleColdStartMuted)
            Spacer(modifier = Modifier.height(48.dp))
        }

        item {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Neko Player", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("一款极简的本地短视频播放器", fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Text("v1.5.0", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun SwitchPreference(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, fontSize = 13.sp, color = Color.Gray, lineHeight = 16.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}