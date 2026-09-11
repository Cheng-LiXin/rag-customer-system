package com.rag.customer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rag.customer.core.Router
import com.rag.customer.core.Route

/**
 * Miuix 范式轻量 UI Kit —— 全部基于 androidx 基础原语手写（避免依赖未经源码验证的
 * Miuix 组件签名），颜色/圆角/字号/排版对齐 Miuix 默认亮色范式：
 *   主蓝 #3482FF · 页面灰 #F7F7F7 · 卡片白 · 大圆角 24 · 正文 15sp。
 */

// ==================== 调色板 ====================
object Miu {
    val primary = Color(0xFF3482FF)
    val gradientEnd = Color(0xFF6FA3FF)
    val primarySoft = Color(0xFFEAF2FF)       // tertiaryContainer
    val pageBg = Color(0xFFF7F7F7)
    val card = Color.White
    val textMain = Color(0xFF111111)
    val textSecondary = Color(0x99000000)     // onSurfaceVariantSummary(60%)
    val textHint = Color(0x59000000)
    val textDisabled = Color(0xFFB2B2B2)
    val textOnPrimary = Color.White
    val fieldBg = Color(0xFFF2F3F7)
    val divider = Color(0xFFE0E0E0)
    val sheetBg = Color(0xFFF7F7F7)
    val danger = Color(0xFFE94634)
    val star = Color(0xFFFFB400)
    val scrim = Color(0x66000000)
    val userBubble = listOf(primary, gradientEnd)
    val agentBubble = Color.White
    val aiBubble = Color.White
    val aiTag = Color(0xFF5BA8FF)
}

val MiuRadiusCard = RoundedCornerShape(24.dp)
val MiuRadiusField = RoundedCornerShape(16.dp)
val MiuRadiusPill = RoundedCornerShape(50)

// ==================== 基础文字 ====================
@Composable
fun Tx(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Miu.textMain,
    size: TextUnit = 15.sp,
    weight: FontWeight = FontWeight.Normal,
    bold: Boolean = false,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    ellipsis: Boolean = false,
    center: Boolean = false,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = if (bold) FontWeight.Bold else weight,
            lineHeight = lineHeight,
            textAlign = if (center) TextAlign.Center else TextAlign.Unspecified,
        ),
        overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        maxLines = maxLines,
    )
}

// ==================== 无涟漪点击 ====================
@Composable
fun Modifier.noRipple(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.then(Modifier.clickable(interactionSource = src, indication = null, onClick = onClick))
}

@Composable
fun Modifier.noRipple(enabled: Boolean, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            enabled = enabled,
            interactionSource = src,
            indication = null,
            onClick = onClick,
        ),
    )
}

// ==================== 顶栏 ====================
@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Miu.card)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .noRipple(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Tx(text = "‹", color = Miu.textMain, size = 30.sp, weight = FontWeight.Medium)
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Tx(text = title, size = 20.sp, bold = true)
                if (subtitle != null) {
                    Tx(
                        text = subtitle,
                        color = Miu.textSecondary,
                        size = 12.sp,
                        maxLines = 1,
                        ellipsis = true,
                    )
                }
            }
            if (actions != null) {
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** 圆形顶栏图标按钮（emoji/字符内容）。 */
@Composable
fun TopIcon(
    glyph: String,
    contentDesc: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .noRipple(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Tx(text = glyph, color = Miu.textMain, size = 20.sp)
    }
}

// ==================== 按钮 ====================
@Composable
fun FilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 50.dp,
) {
    val bg = if (enabled) SolidColor(Miu.primary)
    else SolidColor(Color(0xFFD6D6D6))
    Box(
        modifier = modifier
            .clip(MiuRadiusPill)
            .background(bg)
            .noRipple(enabled = enabled, onClick = onClick)
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        Tx(
            text = text,
            color = if (enabled) Miu.textOnPrimary else Color(0xFF8A8A8A),
            size = 17.sp,
            weight = FontWeight.Medium,
        )
    }
}

@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 50.dp,
    textColor: Color = Miu.textMain,
) {
    Box(
        modifier = modifier
            .clip(MiuRadiusPill)
            .background(if (enabled) Color(0xFFE6E6E6) else Color(0xFFF0F0F0))
            .noRipple(enabled = enabled, onClick = onClick)
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        Tx(
            text = text,
            color = if (enabled) textColor else Miu.textDisabled,
            size = 17.sp,
            weight = FontWeight.Medium,
        )
    }
}

// ==================== 输入框 ====================
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
    maxLines: Int = 1,
    singleLine: Boolean = true,
    contentScaleLarge: Boolean = false,
) {
    val showMask = password && !passwordVisible
    Row(
        modifier = modifier
            .clip(MiuRadiusField)
            .background(Miu.fieldBg)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = Miu.textMain, fontSize = 17.sp),
                cursorBrush = SolidColor(Miu.primary),
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else maxLines,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (showMask) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (imeAction == ImeAction.Done) onDone?.invoke() },
                    onSend = { if (imeAction == ImeAction.Send) onDone?.invoke() },
                    onSearch = { if (imeAction == ImeAction.Search) onDone?.invoke() },
                    onGo = { if (imeAction == ImeAction.Go) onDone?.invoke() },
                ),
                visualTransformation = if (showMask) PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (contentScaleLarge) 46.dp else 50.dp),
                        contentAlignment = if (contentScaleLarge) Alignment.TopStart
                        else Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) {
                            Tx(
                                text = hint,
                                color = Miu.textHint,
                                size = 16.sp,
                                modifier = Modifier.padding(top = if (contentScaleLarge) 13.dp else 0.dp),
                            )
                        }
                        Box(modifier = Modifier.align(if (contentScaleLarge) Alignment.TopStart else Alignment.CenterStart)) {
                            inner()
                        }
                    }
                },
            )
        }
        if (password && onTogglePassword != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .noRipple(onClick = onTogglePassword),
                contentAlignment = Alignment.Center,
            ) {
                Tx(text = if (showMask) "👁" else "🙈", size = 18.sp)
            }
        }
    }
}

// ==================== 居中弹窗 ====================
@Composable
fun DialogSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Miu.scrim)
            .noRipple(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                // 顺序关键：widthIn 必须在 fillMaxWidth 外层，先收紧约束上限，
                // 内层 fillMaxWidth 才会把宽度撑到上限（反过来会 min>max 冲突导致仍铺满屏）。
                .widthIn(max = 348.dp)
                .fillMaxWidth()
                .clip(MiuRadiusCard)
                .background(Miu.card)
                .padding(horizontal = 22.dp, vertical = 20.dp)
                .noRipple(onClick = {}), // 吞掉点击，防止点到 scrim
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tx(text = title, size = 19.sp, bold = true)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
fun DialogActions(
    primary: String,
    onClickPrimary: () -> Unit,
    soft: String? = null,
    onClickSoft: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (soft != null) {
            SoftButton(
                text = soft,
                onClick = { onClickSoft?.invoke() },
                modifier = Modifier.weight(1f),
                height = 46.dp,
                textColor = Miu.textSecondary,
            )
        }
        FilledButton(
            text = primary,
            onClick = onClickPrimary,
            modifier = Modifier.weight(1f),
            height = 46.dp,
        )
    }
}// ==================== 其它小件 ====================
@Composable
fun HLine(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Miu.divider),
    )
}

// ==================== 工具 ====================
object Dates {
    /** "2026-09-08T10:24:12" → "2026-09-08 10:24" */
    fun short(raw: String?): String {
        val s = raw ?: return ""
        val clean = s.replace("T", " ")
        return if (clean.length >= 16) clean.substring(0, 16) else clean
    }
}

// ==================== 单选项选择弹层 ====================
@Composable
fun OptionsSheet(
    title: String,
    options: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    DialogSheet(visible = true, onDismiss = onClose, title = title) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            options.forEach { opt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .noRipple { onPick(opt) }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tx(text = opt, color = Miu.textMain, size = 16.sp, modifier = Modifier.weight(1f))
                    if (opt == selected) {
                        Tx(text = "✓", color = Miu.primary, size = 16.sp, bold = true)
                    }
                }
            }
            if (options.isEmpty()) {
                Tx(text = "暂无选项", color = Miu.textHint, size = 14.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        DialogActions(primary = "取消", onClickPrimary = onClose)
    }
}

/** 未登录占位：提示 + 去登录。 */
@Composable
fun LoginGate(hint: String, buttonText: String = "去登录") {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Tx(text = "🔐", size = 46.sp)
        Spacer(Modifier.height(14.dp))
        Tx(text = hint, color = Miu.textSecondary, size = 14.sp, center = true)
        Spacer(Modifier.height(22.dp))
        FilledButton(text = buttonText, onClick = { Router.push(Route.Login) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun PageBackground(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Miu.pageBg),
        content = content,
    )
}

/** 头像（首字 + 品牌渐变圆）。 */
@Composable
fun Avatar(name: String, size: Dp = 40.dp, gradient: List<Color> = Miu.userBubble) {
    val initial = name.trim().firstOrNull()?.toString()?.takeIf { it.isNotBlank() } ?: "客"
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Tx(text = initial, color = Color.White, size = (size.value * 0.42f).sp, weight = FontWeight.SemiBold)
    }
}

/** 行列表项：左侧内容 + 右侧 chevron，点击整行。 */
@Composable
fun RowItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .noRipple(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
fun Chevron() {
    Tx(text = "›", color = Miu.textHint, size = 24.sp, weight = FontWeight.Light)
}

/** 左图标 + 标题副题的设置行（图标用 emoji/字符 + 彩色圆底）。 */
@Composable
fun SettingRow(
    iconGlyph: String,
    iconBg: Color,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    RowItem(onClick = onClick) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Tx(text = iconGlyph, size = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Tx(text = title, size = 16.sp)
            if (subtitle != null) {
                Tx(text = subtitle, color = Miu.textSecondary, size = 12.sp, maxLines = 1, ellipsis = true)
            }
        }
        Chevron()
    }
}

