package com.rag.customer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 冷启动标记：进程级静态变量——冷启动（进程重建）为 true，进入主界面后置 false。
 * Activity 因旋转 / 切后台重建不会重置该值，因此落地页只在“真正冷启动”时展示一次。
 */
object SplashGate {
    var pending by mutableStateOf(true)
}

/**
 * 科技感落地页（仅冷启动展示）。浅色天空渐变 + 角落光斑 + 中央机器人圆徽，
 * 徽章背后有呼吸光晕 / 三条脉冲光圈 / 慢速自转射线与环绕轨道光点。
 * 「快速开始」= 实心主蓝 + 柔和蓝光按钮，点击后淡出进入主界面（首页 Chat）。
 * 视觉沿用 Miuix 亮色范式（主蓝 #3482FF），不做深色赛博风，与 app 主题不割裂。
 */
@Composable
fun SplashScreen(onEnter: () -> Unit) {
    // 入场淡入（一次性，700ms）
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val fade by animateFloatAsState(if (appeared) 1f else 0f, tween(700), label = "splashFade")

    // 无限光效：三条脉冲光圈（错峰扩散淡出）、光晕呼吸、光点/射线缓慢自转
    val tr = rememberInfiniteTransition(label = "splashFx")
    val ring1 = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "ring1")
    val ring2 = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(3900, easing = LinearEasing), RepeatMode.Restart, initialStartOffset = StartOffset(1300)), label = "ring2")
    val ring3 = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(4800, easing = LinearEasing), RepeatMode.Restart, initialStartOffset = StartOffset(2600)), label = "ring3")
    val spin = tr.animateFloat(0f, 360f, infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val pulse = tr.animateFloat(0.82f, 1.18f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade }
            .background(Brush.verticalGradient(listOf(Color(0xFFF2F7FF), Color(0xFFE7F0FF), Color(0xFFD9E7FF)))),
    ) {
        // 氛围光斑：右上 / 左下两团淡蓝径向渐变
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF9DC3FF).copy(alpha = 0.16f), Color.Transparent),
                            center = Offset(size.width, 0f),
                            radius = size.width * 0.62f,
                        ),
                        radius = size.width * 0.62f,
                        center = Offset(size.width, 0f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF9DC3FF).copy(alpha = 0.13f), Color.Transparent),
                            center = Offset(0f, size.height),
                            radius = size.width * 0.58f,
                        ),
                        radius = size.width * 0.58f,
                        center = Offset(0f, size.height),
                    )
                },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.9f))
            // 中央徽章：光晕 / 光圈 / 射线 / 轨道光点 + 白色机器人圆徽
            SplashEmblem(pulse.value, ring1.value, ring2.value, ring3.value, spin.value)
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tx(text = "燕山大学", color = Miu.textMain, size = 27.sp, weight = FontWeight.Bold)
                Tx(text = " 智能客服系统", color = Miu.primary, size = 27.sp, weight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Tx(
                text = "基于校园知识库的 RAG 智能问答系统",
                color = Miu.textSecondary,
                size = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SplashPill("🎓 校园问答")
                SplashPill("📄 资料检索")
                SplashPill("💬 人工客服")
            }
            Spacer(Modifier.weight(1.25f))
            // 快速开始：实心主蓝 + 柔和蓝光（沿用 Miuix 实心按钮范式）
            val glow = Color(0xFF6FA3FF).copy(alpha = 0.55f)
            Box(
                modifier = Modifier
                    .width(272.dp)
                    .shadow(16.dp, CircleShape, ambientColor = glow, spotColor = glow)
                    .clip(CircleShape)
                    .background(Miu.primary)
                    .noRipple(onClick = onEnter)
                    .height(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tx(text = "快速开始", color = Color.White, size = 18.sp, weight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Tx(text = "→", color = Color.White, size = 20.sp, weight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(20.dp))
            Tx(
                text = "智能客服系统 · 毕业设计演示",
                color = Miu.textHint,
                size = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SplashEmblem(pulse: Float, r1: Float, r2: Float, r3: Float, spinDeg: Float) {
    Box(modifier = Modifier.size(180.dp), contentAlignment = Alignment.Center) {
        // 光效层（drawBehind 的 DrawScope 本身即 Density，可直接用 Dp.toPx()）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val c = center
                    // 呼吸光晕
                    val haloR = (50.dp * pulse).toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Miu.primary.copy(alpha = 0.30f), Miu.primary.copy(alpha = 0.10f), Color.Transparent),
                            center = c,
                            radius = haloR * 2.2f,
                        ),
                        radius = haloR * 2.2f,
                        center = c,
                    )
                    // 脉冲光圈（三条错峰扩散、淡出）
                    val ringColor = Color(0xFF4D8DFF)
                    listOf(r1, r2, r3).forEach { t ->
                        val rad = 60.dp.toPx() + t * 90.dp.toPx()
                        drawCircle(
                            color = ringColor.copy(alpha = (1f - t) * 0.34f),
                            radius = rad,
                            center = c,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                    // 慢速自转射线（科技光效，低透明度）
                    val rot = Math.toRadians(spinDeg.toDouble())
                    val rayCol = Color(0xFF5B9CFF).copy(alpha = 0.055f)
                    for (i in 0 until 24) {
                        val a = rot + i * (2.0 * PI / 24.0)
                        val dx = cos(a).toFloat()
                        val dy = sin(a).toFloat()
                        drawLine(
                            color = rayCol,
                            start = Offset(c.x + dx * 70.dp.toPx(), c.y + dy * 70.dp.toPx()),
                            end = Offset(c.x + dx * 132.dp.toPx(), c.y + dy * 132.dp.toPx()),
                            strokeWidth = 1.6.dp.toPx(),
                        )
                    }
                    // 轨道光点（绕徽章缓慢公转，带柔光）
                    val dotR = 76.dp.toPx()
                    val dx = cos(rot).toFloat()
                    val dy = sin(rot).toFloat()
                    val dotC = Offset(c.x + dx * dotR, c.y + dy * dotR)
                    drawCircle(
                        color = Color(0xFF7FB0FF).copy(alpha = 0.18f),
                        radius = 12.dp.toPx(),
                        center = dotC,
                    )
                    drawCircle(
                        color = Color(0xFF4D8DFF).copy(alpha = 0.9f),
                        radius = 4.dp.toPx(),
                        center = dotC,
                    )
                },
        )
        // 主徽章：白色圆卡（柔和蓝光投影 + 渐变描边环）+ 品牌渐变对话气泡（内含白色星芒）
        Box(
            modifier = Modifier
                .size(118.dp)
                .shadow(
                    elevation = 20.dp,
                    shape = CircleShape,
                    ambientColor = Color(0xFF4D8DFF).copy(alpha = 0.28f),
                    spotColor = Color(0xFF6FA3FF).copy(alpha = 0.45f),
                )
                .clip(CircleShape)
                .background(Color.White)
                .drawBehind {
                    // 细渐变描边环（左上主蓝 → 右下浅蓝），替代原平面灰蓝描边
                    drawCircle(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Miu.primary.copy(alpha = 0.40f),
                                Miu.gradientEnd.copy(alpha = 0.14f),
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height),
                        ),
                        radius = size.minDimension / 2f - 1.2.dp.toPx(),
                        style = Stroke(width = 1.6.dp.toPx()),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // 品牌渐变对话气泡 + 白色四角星芒（纯矢量 logo 标记，替代原 emoji 机器人）
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        val bubbleH = h * 0.76f
                        val r = bubbleH * 0.26f
                        val bubble = Path().apply {
                            addRoundRect(RoundRect(0f, 0f, w, bubbleH, CornerRadius(r, r)))
                            // 左下气泡尾（与气泡同色填充，无缝拼接）
                            moveTo(w * 0.24f, bubbleH - r * 0.4f)
                            lineTo(w * 0.16f, h * 0.99f)
                            lineTo(w * 0.50f, bubbleH - r * 0.4f)
                            close()
                        }
                        drawPath(
                            path = bubble,
                            brush = Brush.linearGradient(
                                colors = listOf(Miu.primary, Miu.gradientEnd),
                                start = Offset(0f, 0f),
                                end = Offset(w, h),
                            ),
                        )
                        // 白色四角星芒（AI 标识）
                        drawPath(
                            path = sparklePath(w / 2f, bubbleH / 2f, bubbleH * 0.30f),
                            color = Color.White,
                        )
                    },
            )
        }
    }
}

/** 四角星芒路径（凹曲线，用作徽章内的 AI 标识）。 */
private fun sparklePath(cx: Float, cy: Float, r: Float): Path {
    val k = r * 0.17f
    return Path().apply {
        moveTo(cx, cy - r)
        quadraticTo(cx + k, cy - k, cx + r, cy)
        quadraticTo(cx + k, cy + k, cx, cy + r)
        quadraticTo(cx - k, cy + k, cx - r, cy)
        quadraticTo(cx - k, cy - k, cx, cy - r)
        close()
    }
}

@Composable
private fun SplashPill(text: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.82f))
            .border(1.dp, Color(0xFFDCE9FF), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Tx(text = text, color = Color(0xFF3E6EE0), size = 12.sp, weight = FontWeight.Medium)
    }
}
