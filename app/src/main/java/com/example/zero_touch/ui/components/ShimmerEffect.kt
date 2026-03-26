package com.example.zero_touch.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.zero_touch.ui.theme.ZtShimmerBase
import com.example.zero_touch.ui.theme.ZtShimmerHighlight

/**
 * Shimmer loading placeholder — matches the compact topic+card layout.
 */
@Composable
fun ShimmerCardList(
    count: Int = 3,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(count) {
            ShimmerTopicCard()
        }
    }
}

@Composable
private fun ShimmerTopicCard() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(ZtShimmerBase, ZtShimmerHighlight, ZtShimmerBase),
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim + 200f, 0f)
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = androidx.compose.ui.graphics.Color.White,
        shadowElevation = 0.5.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Topic header skeleton
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(shimmerBrush, RoundedCornerShape(3.dp))
                    )
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(14.dp)
                            .background(shimmerBrush, RoundedCornerShape(3.dp))
                    )
                }
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(14.dp)
                        .background(shimmerBrush, RoundedCornerShape(3.dp))
                )
            }
            // Summary line
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(10.dp)
                    .background(shimmerBrush, RoundedCornerShape(3.dp))
            )
            // Compact card rows
            repeat(2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(shimmerBrush, CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(10.dp)
                            .background(shimmerBrush, RoundedCornerShape(2.dp))
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .background(shimmerBrush, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}
