package com.neko.player

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun DiagnosticsPage(model: PlayerViewModel, onBack: () -> Unit) {
    val recorder = model.diagnostics
    val context = LocalContext.current
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) model.saveDiagnostics(it)
    }
    DiagnosticsContent(recorder.running, recorder.hasReport, recorder.status, model.diagnosticsExportStatus,
        onToggle = { if (recorder.running) recorder.stop() else recorder.start() },
        onShare = { model.shareDiagnostics { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("性能诊断报告", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享诊断报告"))
        } }, onSave = { destination.launch("NekoPlayer-${BuildConfig.VERSION_NAME}-diagnostics.json") }, onBack,
        onCopy = model::copyDiagnosticsSummary)
}

@Composable
internal fun DiagnosticsContent(running: Boolean, hasReport: Boolean, status: String, exportStatus: String?,
    onToggle: () -> Unit, onShare: () -> Unit, onSave: () -> Unit, onBack: () -> Unit, onCopy: () -> Unit = {}) {
    SettingsScaffold("性能诊断", onBack = onBack) { padding, scroll ->
        SettingsList(padding, scroll, bottom = 28) {
            item {
                SettingsGroup("记录") {
                    BasicComponent(title = "当前状态", summary = status)
                    ArrowPreference(title = if (running) "停止记录" else "开始新记录", onClick = onToggle,
                        summary = if (running) "返回播放页正常刷视频，测试结束后回这里停止" else "开启后记录，最多 30 分钟；开始新记录会替换上一次报告",
                        modifier = Modifier.testTag("diagnostics-toggle"))
                }
            }
            item {
                SettingsGroup("导出") {
                    ArrowPreference(title = "复制报告摘要", summary = "复制后直接粘贴到聊天，不方便传文件时也能反馈",
                        enabled = hasReport || running, onClick = onCopy, modifier = Modifier.testTag("copy-diagnostics"))
                    ArrowPreference(title = "分享报告", summary = "通过手机分享菜单发送 JSON 文件",
                        enabled = hasReport || running, onClick = onShare, modifier = Modifier.testTag("share-diagnostics"))
                    ArrowPreference(title = "保存报告到文件", summary = exportStatus ?: "可保存到下载目录，再通过本对话的附件按钮上传",
                        enabled = hasReport || running, onClick = onSave, modifier = Modifier.testTag("save-diagnostics"))
                }
            }
            item {
                SettingsGroup("怎么测试") {
                    BasicComponent(title = "正常刷 5 分钟", summary = "保持平常使用的亮度和音量，期间快速连划、双击点赞和长按加速几次")
                    BasicComponent(title = "切到后台 2 分钟", summary = "先关闭后台播放，离开应用后再回来；可另开一轮单独测试后台播放")
                    BasicComponent(title = "结束后导出", summary = "停止记录，保存 JSON，并告诉我哪里卡顿、有没有黑屏或误触。耗电测试建议再单独记录 20 分钟，期间不要充电")
                }
            }
            item {
                SettingsGroup("报告内容") {
                    BasicComponent(title = "本机记录，手动分享", summary = "包含机型、系统版本、内存、CPU、首帧回调耗时、缓冲、丢帧和整机电量变化；不包含视频、视频文件名或目录路径，不自动上传")
                    BasicComponent(title = "数据的范围", summary = "首帧数值来自渲染回调。电量变化是整机数据，受屏幕和其他应用影响；诊断采样本身也有少量开销")
                }
            }
        }
    }
}
