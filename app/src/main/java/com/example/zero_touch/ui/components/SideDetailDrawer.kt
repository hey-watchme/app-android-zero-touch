package com.example.zero_touch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtScrim
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SideDetailDrawer(
    title: String,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val configuration = LocalConfiguration.current
    val drawerWidthDp = (configuration.screenWidthDp * 0.82f).dp
    val density = LocalDensity.current
    val drawerWidthPx = with(density) { drawerWidthDp.toPx() }
    val scrollState = rememberScrollState()
    val scrimInteraction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()

    val offsetX = remember { Animatable(drawerWidthPx) }
    val scrimAlpha = remember { Animatable(0f) }
    val isDismissing = remember { mutableStateOf(false) }

    // Enter
    LaunchedEffect(Unit) {
        launch {
            offsetX.animateTo(
                0f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
            )
        }
        launch {
            scrimAlpha.animateTo(
                0.32f,
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            )
        }
    }

    // Exit
    LaunchedEffect(isDismissing.value) {
        if (isDismissing.value) {
            launch { scrimAlpha.animateTo(0f, tween(200, easing = FastOutSlowInEasing)) }
            offsetX.animateTo(drawerWidthPx, tween(240, easing = FastOutSlowInEasing))
            onClose()
        }
    }

    Dialog(
        onDismissRequest = { isDismissing.value = true },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimAlpha.value / 0.32f }
                    .background(ZtScrim.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = scrimInteraction,
                        indication = null
                    ) { isDismissing.value = true }
            )

            // Drawer
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(drawerWidthDp)
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (offsetX.value > drawerWidthPx * 0.3f) {
                                        isDismissing.value = true
                                    } else {
                                        offsetX.animateTo(
                                            0f,
                                            spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
                                        )
                                    }
                                }
                            }
                        ) { _, dragAmount ->
                            if (dragAmount > 0) {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(0f, drawerWidthPx)
                                scope.launch {
                                    offsetX.snapTo(newOffset)
                                    scrimAlpha.snapTo(0.32f * (1f - newOffset / drawerWidthPx))
                                }
                            }
                        }
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = { isDismissing.value = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Close",
                                tint = ZtCaption,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    content()
                }
            }
        }
    }
}
