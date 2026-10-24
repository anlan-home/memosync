package com.family.memo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 悬浮玻璃胶囊导航栏（参考 Miuix GlassNavigationBar / HyperOS 4）：
 * - 整条是浮起的胶囊，柔光玻璃质感（半透明 + 高光描边 + 柔和投影）
 * - 选中项背后有一个「气泡」胶囊，随选中项用弹簧动画滑动
 * - 图标 28dp / 文字 11dp，未选中项不压暗，靠气泡指示位置
 */
@Composable
fun GlassBottomBar(
    items: List<Pair<ImageVector, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val barHeight = 54.dp
    val bubbleHeight = 48.dp
    val overhang = 5.dp

    // 玻璃本体：半透明底 + 顶部高光 + 描边
    val glassFill = if (dark) Color(0xFF1C1C20).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.74f)
    val rimTop = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.9f)
    val rimBottom = if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.35f)
    val bubbleFill = if (dark) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val selectedTint = MaterialTheme.colorScheme.primary
    val unselectedTint = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = 8.dp)
            .shadow(
                elevation = if (dark) 6.dp else 10.dp,
                shape = RoundedCornerShape(barHeight / 2),
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.16f),
                clip = false,
            )
            .clip(RoundedCornerShape(barHeight / 2))
            .background(glassFill)
            // 柔光：上亮下暗的竖向微渐变（玻璃高光）
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.06f else 0.35f),
                    0.45f to Color.Transparent,
                    1f to Color.White.copy(alpha = if (dark) 0.02f else 0.10f),
                ),
            )
            .border(
                width = 0.7.dp,
                brush = Brush.verticalGradient(listOf(rimTop, rimBottom)),
                shape = RoundedCornerShape(barHeight / 2),
            )
            .height(barHeight),
        contentAlignment = Alignment.TopStart,
    ) {
        val slot = maxWidth / items.size
        // 气泡位置：弹簧动画滑到选中项
        val bubbleOffset by animateDpAsState(
            targetValue = slot * selectedIndex - overhang,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "glassBubbleOffset",
        )

        // 选中气泡
        Box(
            Modifier
                .offset(x = bubbleOffset, y = (barHeight - bubbleHeight) / 2)
                .width(slot + overhang * 2)
                .height(bubbleHeight)
                .clip(RoundedCornerShape(bubbleHeight / 2))
                .background(bubbleFill),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .height(barHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, (icon, label) ->
                val selected = index == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val tint by animateColorAsState(
                    targetValue = when {
                        pressed -> selectedTint.copy(alpha = 0.7f)
                        selected -> selectedTint
                        else -> unselectedTint
                    },
                    label = "glassTabTint",
                )
                Column(
                    Modifier
                        .width(slot)
                        .height(bubbleHeight)
                        .clip(RoundedCornerShape(bubbleHeight / 2))
                        .selectable(
                            selected = selected,
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onSelect(index) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(icon, label, Modifier.size(28.dp), tint = tint)
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = tint,
                    )
                }
            }
        }
    }
}
