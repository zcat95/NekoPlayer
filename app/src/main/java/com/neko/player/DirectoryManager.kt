package com.neko.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun DirectoryManager(model: PlayerViewModel, onAdd: () -> Unit,
                              onReauthorize: (String) -> Unit, onBack: () -> Unit) {
    DirectoryManagerContent(model.directories, model.scanning, onAdd, onReauthorize,
        model::setDirectoryEnabled, model::removeDirectory, model::scan, onBack)
}

@Composable
internal fun DirectoryManagerContent(directories: List<VideoDirectory>, scanning: Boolean,
    onAdd: () -> Unit, onReauthorize: (String) -> Unit, onEnabled: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit, onScan: () -> Unit, onBack: () -> Unit) {
    SettingsScaffold("视频目录", onBack = onBack) { padding, scroll ->
        SettingsList(padding, scroll, bottom = 28) {
            item {
                SettingsGroup("管理") {
                    ArrowPreference(title = "添加目录", summary = "多个目录的视频会合并播放", onClick = onAdd,
                        modifier = Modifier.testTag("add-directory"))
                    ArrowPreference(title = if (scanning) "正在扫描…" else "重新扫描",
                        summary = "只扫描所选目录当前层级，子目录可单独添加", enabled = !scanning, onClick = onScan)
                }
            }
            if (directories.isEmpty()) item {
                SettingsGroup("目录列表") { BasicComponent(title = "尚未添加目录", summary = "点击上方添加目录并授权读取") }
            }
            directories.forEach { directory ->
                item(key = directory.uri) {
                    SettingsGroup(directoryLabel(directory.uri)) {
                        SwitchPreference(directory.enabled, { onEnabled(directory.uri, it) }, "参与播放",
                            summary = when {
                                !directory.enabled -> "已停用"
                                scanning -> "正在扫描…"
                                !directory.readable -> "无法读取，请重新授权"
                                else -> "共 ${directory.videoCount} 个视频"
                            }, modifier = Modifier.testTag("directory-enabled:${directory.uri}"))
                        ArrowPreference(title = "重新授权", summary = "目录被移动或授权失效时使用",
                            onClick = { onReauthorize(directory.uri) })
                        ArrowPreference(title = "移除目录", summary = "保留手机中的视频、收藏和已看记录",
                            onClick = { onRemove(directory.uri) }, modifier = Modifier.testTag("remove-directory:${directory.uri}"))
                    }
                }
            }
        }
    }
}
