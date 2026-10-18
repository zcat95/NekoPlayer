package com.neko.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

enum class AppTheme(val preferenceValue: String, val label: String, val colorSchemeMode: ColorSchemeMode) {
    SYSTEM("system", "跟随系统", ColorSchemeMode.System),
    LIGHT("light", "浅色", ColorSchemeMode.Light),
    DARK("dark", "深色", ColorSchemeMode.Dark);

    companion object {
        fun fromPreference(value: String?): AppTheme = entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}

@Composable
internal fun NekoTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val controller = remember(theme) { ThemeController(colorSchemeMode = theme.colorSchemeMode) }
    MiuixTheme(controller = controller, content = content)
}
