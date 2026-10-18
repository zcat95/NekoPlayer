// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
// Simplified from HyperChanger's About page (5d84f123) for Neko Player.
package com.neko.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val DeveloperProfile = "https://github.com/zcat95"
private const val DeveloperAvatar = "https://avatars.githubusercontent.com/u/8362354?v=4&s=256"

@Composable
internal fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val cards = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = if (dark) .60f else .65f))
    SettingsScaffold("关于", onBack, showLargeTitle = false,
        pageBackground = { AboutMaterialBackground(Modifier.matchParentSize()) }) { padding, scroll ->
        LazyColumn(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 4.dp, 16.dp, padding.calculateBottomPadding() + 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { AboutBrandHeader(dark) }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, colors = cards) {
                    Row(Modifier.fillMaxWidth().clickable { openAboutUrl(context, DeveloperProfile) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        DeveloperAvatar()
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text("zcat95", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                            Text("github.com/zcat95", style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp))
                        }
                        Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(22.dp),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary))
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, colors = cards) {
                    BasicComponent(title = "版本号", summary = BuildConfig.VERSION_NAME)
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, colors = cards) {
                    ArrowPreference(title = "本项目基于 MIUIX 构建",
                        onClick = { openAboutUrl(context, "https://compose-miuix-ui.github.io/miuix/") })
                }
            }
        }
    }
}

@Composable
private fun AboutBrandHeader(dark: Boolean) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(310.dp).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        val width = maxWidth - 18.dp
        val logoSize = width * (.94f / 3.91f) * 2.78f * .8f * .95f
        val textWidth = width * (2.97f / 3.91f)
        AndroidView(factory = { MaterialBrandView(it) }, update = {
            it.configure(with(density) { logoSize.roundToPx() }, with(density) { textWidth.roundToPx() }, dark)
        }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun DeveloperAvatar() {
    val context = LocalContext.current.applicationContext
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "github-zcat95-avatar.png")
            val cached = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            withContext(Dispatchers.Main) { bitmap = cached }
            if (cached == null || System.currentTimeMillis() - file.lastModified() > 86_400_000L) {
                val loaded = runCatching {
                    val connection = URL(DeveloperAvatar).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    try {
                        check(connection.responseCode == 200)
                        connection.inputStream.use(BitmapFactory::decodeStream)?.also { image ->
                            val temp = File(context.cacheDir, "github-zcat95-avatar.tmp")
                            temp.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            if (!temp.renameTo(file)) temp.delete()
                        }
                    } finally { connection.disconnect() }
                }.getOrNull()
                if (loaded != null) withContext(Dispatchers.Main) { bitmap = loaded }
            }
        }
    }
    val value = bitmap
    if (value != null) Image(value.asImageBitmap(), "zcat95 的 GitHub 头像", Modifier.size(52.dp).clip(CircleShape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(52.dp).clip(CircleShape).background(MiuixTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text("Z", fontWeight = FontWeight.Bold)
    }
}

private fun openAboutUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { Toast.makeText(context, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show() }
}
