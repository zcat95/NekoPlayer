package com.neko.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.theme.MiuixTheme

class BetaSettingsPagesTest {
    @get:Rule val rule = createComposeRule()

    @Test fun directoriesCanBeDisabledAndRemovedWithoutAffectingOtherEntries() {
        val first = "content://provider/tree/a"
        val second = "content://provider/tree/b"
        val entries = mutableStateOf(listOf(VideoDirectory(first, readable = true, videoCount = 3),
            VideoDirectory(second, readable = true, videoCount = 4)))
        rule.setContent { MiuixTheme {
            DirectoryManagerContent(entries.value, false, {}, {}, { uri, enabled ->
                entries.value = entries.value.map { if (it.uri == uri) it.copy(enabled = enabled) else it }
            }, { uri -> entries.value = entries.value.filterNot { it.uri == uri } }, {}, {})
        } }
        rule.onNodeWithTag("directory-enabled:$first").performScrollTo().performClick()
        rule.runOnIdle { assertFalse(entries.value.first().enabled) }
        rule.onNodeWithTag("remove-directory:$first").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(second), entries.value.map { it.uri }) }
    }

    @Test fun diagnosticsRequireARecordBeforeExportAndOfferBothPhoneExportPaths() {
        val running = mutableStateOf(false)
        var shares = 0
        var saves = 0
        rule.setContent { MiuixTheme {
            DiagnosticsContent(running.value, false, "测试", null, { running.value = !running.value },
                { shares++ }, { saves++ }, {})
        } }
        rule.onNodeWithTag("share-diagnostics").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("diagnostics-toggle").performScrollTo().performClick()
        rule.onNodeWithTag("share-diagnostics").performScrollTo().performClick()
        rule.onNodeWithTag("save-diagnostics").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, shares); assertEquals(1, saves) }
    }
}
