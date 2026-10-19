package com.neko.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SettingsPage(model: PlayerViewModel, onChangeFolder: () -> Unit, onBack: () -> Unit,
                          onAbout: () -> Unit, onDiagnostics: () -> Unit = {}) {
    LaunchedEffect(model.controller) { model.refreshCacheUsage() }
    val cacheSummary = if (model.playbackCacheBytes == null) model.cacheStatus ?: "正在读取…" else
        "播放缓存 ${formatCacheBytes(model.playbackCacheBytes!!)} · 预览 ${formatCacheBytes(model.frames.bytes)}"
    SettingsContent(model.settings, model.directorySummary, model.videos.size, model.playlist(1).size,
        model::updateSettings, onChangeFolder, onAbout, onBack,
        cacheSummary, model.clearingCache, model.cacheStatus, model::clearCache, onDiagnostics)
}

internal fun formatCacheBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
}

@Composable
internal fun SettingsContent(value: PlayerSettings, directory: String?, videoCount: Int, favoriteCount: Int,
                             update: (PlayerSettings) -> Unit,
                             onChangeFolder: () -> Unit, onAbout: () -> Unit, onBack: (() -> Unit)? = null,
                             cacheSummary: String = "0 B", clearingCache: Boolean = false,
                             cacheStatus: String? = null, onClearCache: () -> Unit = {}, onDiagnostics: () -> Unit = {}) {
    SettingsScaffold("设置", onBack = onBack) { padding, scroll ->
        SettingsList(padding, scroll, bottom = 28) {
            item {
                SettingsGroup("数据与存储") {
                    ArrowPreference(title = "视频目录", summary = directory ?: "尚未选择路径", onClick = onChangeFolder)
                    BasicComponent(title = "已扫描视频", summary = "共 $videoCount 个视频")
                    BasicComponent(title = "已启用目录的收藏", summary = "共 $favoriteCount 个视频")
                    BasicComponent(title = "缓存占用", summary = cacheSummary)
                    ArrowPreference(title = if (clearingCache) "正在清理…" else "清理缓存",
                        summary = cacheStatus ?: "清理播放缓存和预览图，保留视频、收藏与已看记录",
                        enabled = !clearingCache, onClick = onClearCache,
                        modifier = Modifier.testTag("clear-cache"))
                }
            }
            item {
                SettingsGroup("测试与反馈") {
                    ArrowPreference(title = "性能诊断", summary = "记录测试过程，用手机导出报告", onClick = onDiagnostics,
                        modifier = Modifier.testTag("performance-diagnostics"))
                }
            }
            item {
                SettingsGroup("外观") {
                    GlassDropdownPreference(items = AppTheme.entries.map { it.label },
                        selectedIndex = value.theme.ordinal, title = "主题模式",
                        modifier = Modifier.testTag("theme-mode"),
                        onSelectedIndexChange = { update(value.copy(theme = AppTheme.entries[it])) })
                }
            }
            item {
                SettingsGroup("播放偏好") {
                    SwitchPreference(value.shuffle, { update(value.copy(shuffle = it)) },
                        "随机播放", summary = "打乱列表播放，关闭则按名称顺序")
                    SwitchPreference(value.preferUnwatched, { update(value.copy(preferUnwatched = it)) },
                        "未看优先", summary = "随机播放时优先播放未看视频，记录仅保存在本机",
                        modifier = Modifier.testTag("prefer-unwatched"),
                        enabled = value.shuffle)
                    SwitchPreference(value.autoNext, { update(value.copy(autoNext = it)) },
                        "自动播放下一个", summary = "播完接续下一个，关闭则单视频循环")
                    SwitchPreference(value.crop, { update(value.copy(crop = it)) },
                        "画面全屏裁切", summary = if (value.crop) "裁切填充" else "保留原始比例")
                }
            }
            item {
                SettingsGroup("交互与系统") {
                    SwitchPreference(value.global2x, { update(value.copy(global2x = it)) },
                        "全局 2.0x 倍速", summary = "关闭后可长按画面临时倍速")
                    SwitchPreference(value.backgroundPlay, { update(value.copy(backgroundPlay = it)) },
                        "后台播放", summary = "切到后台或锁屏后继续播放，可通过系统媒体通知控制")
                    SwitchPreference(value.coldMute, { update(value.copy(coldMute = it)) },
                        "启动自动静音", summary = "下次启动播放服务时静音，按系统音量键解除")
                }
            }
            item {
                SettingsGroup("应用信息") { ArrowPreference(title = "关于", onClick = onAbout) }
            }
        }
    }
}
