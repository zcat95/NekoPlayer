package com.neko.player

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun Modifier.videoGestures(key: Any, active: Boolean, global2x: Boolean,
                           onTap: () -> Unit, onDoubleTap: () -> Unit,
                           onAcceleration: (Boolean) -> Boolean,
                           onHaptic: (() -> Unit)? = null): Modifier {
    val speed by rememberUpdatedState(global2x)
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val acceleration by rememberUpdatedState(onAcceleration)
    val haptic by rememberUpdatedState(onHaptic)
    val view = LocalView.current
    return pointerInput(key, active) {
        if (!active) return@pointerInput
        var held = false
        detectTapGestures(
            onTap = { if (!held) tap() },
            onDoubleTap = { if (!held) doubleTap() },
            // Do not install onLongPress: it consumes movement after the system long-press timeout.
            // The pager must remain free to cancel a hold and claim either swipe direction.
            onPress = {
                held = false
                coroutineScope {
                    val job = launch {
                        delay(200)
                        held = true
                        if (!speed && acceleration(true)) {
                            haptic?.invoke() ?: view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    }
                    try { tryAwaitRelease() } finally {
                        job.cancel()
                        acceleration(false)
                    }
                }
            },
        )
    }
}
