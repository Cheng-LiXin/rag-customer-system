package com.rag.customer.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rag.customer.core.Router
import com.rag.customer.core.Route
import com.rag.customer.core.Session
import com.rag.customer.core.ToastHost
import com.rag.customer.data.SourceData
import com.rag.customer.state.ChatModel
import com.rag.customer.state.HumanStatus
import com.rag.customer.state.MsgRole
import kotlinx.coroutines.delay

// 镜像 Web 端 ChatHome.vue 首页预设卡片（图标 + 文案逐条对齐）
private val Suggestions = listOf(
    "📚" to "燕山大学的招生政策是什么？",
    "🎯" to "学校有哪些特色专业？",
    "🏠" to "宿舍条件怎么样？",
    "🎫" to "如何办理校园一卡通？",
    "🚌" to "新生报到流程是怎样的？",
    "🎓" to "奖学金和助学金政策有哪些？",
)

/** 智能问答主页（M2/M3）：空态建议 → 问答流 → 转人工 WS → 满意度评价。 */
@Composable
fun ChatScreen() {
    var input by remember { mutableStateOf("") }
    var sourceDialog by remember { mutableStateOf<SourceData?>(null) }

    // 历史会话回放入口：History 点某会话 → 回到本页自动加载
    LaunchedEffect(Unit) {
        Session.reopenConvId?.let { c ->
            Session.reopenConvId = null
            ChatModel.replayConversation(c)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Miu.pageBg),
    ) {
        ChatHeader()
        if (ChatModel.msgs.isEmpty()) {
            GreetingPane(modifier = Modifier.weight(1f), onAsk = { q -> ChatModel.send(q) })
        } else {
            MessageList(modifier = Modifier.weight(1f), sourceDialog = { sourceDialog = it })
        }
        HumanBar()
        InputPanel(
            value = input,
            onValueChange = { input = it },
            onSend = { q -> if (ChatModel.send(q)) input = "" },
        )
    }

    sourceDialog?.let { src ->
        SourceDialog(src, onClose = { sourceDialog = null })
    }
}

// ==================== 顶栏 ====================
@Composable
private fun ChatHeader() {
    val logged = Session.isLoggedIn
    val subtitle = when {
        ChatModel.humanStatus == HumanStatus.Connected -> "与人工客服对话中"
        ChatModel.humanStatus == HumanStatus.Queuing -> "排队中 · 前方 ${ChatModel.queuePos} 位"
        !logged -> "游客 · 可体验智能问答"
        else -> (Session.roles.firstOrNull()?.removePrefix("ROLE_")?.let { "角色：$it · " } ?: "") + "智能助手随时在线"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Miu.card)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(Miu.userBubble)),
                contentAlignment = Alignment.Center,
            ) {
                Tx(text = "智", color = Color.White, size = 17.sp, weight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Tx(text = "智能客服助手", size = 18.sp, bold = true)
                Tx(
                    text = subtitle,
                    color = Miu.textSecondary,
                    size = 11.sp,
                    maxLines = 1,
                    ellipsis = true,
                )
            }
            TopIcon("🕘", onClick = { Router.push(Route.History) })
            if (logged) {
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .noRipple { Router.push(Route.Profile) },
                ) {
                    Avatar(name = Session.displayName, size = 34.dp)
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .noRipple { Router.push(Route.Login) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE6E6E6)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Tx(text = "登", color = Miu.textSecondary, size = 14.sp)
                    }
                }
            }
        }
    }
}

// ==================== 空态欢迎 + 建议 ====================
@Composable
private fun GreetingPane(modifier: Modifier = Modifier, onAsk: (String) -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3482FF), Color(0xFF6FA3FF))))
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Tx(text = "✦", color = Color.White, size = 22.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Tx(text = "你好，我是智能客服助手", color = Color.White, size = 19.sp, bold = true)
                    Tx(text = "基于知识库的招生 / 教务 / 校园智能问答", color = Color.White.copy(alpha = 0.85f), size = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Tx(text = "试试这样问", color = Miu.textSecondary, size = 13.sp, bold = true)
        Spacer(Modifier.height(10.dp))
        Suggestions.forEach { (emoji, s) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .noRipple { onAsk(s) }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Miu.card)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tx(text = emoji, size = 18.sp)
                Spacer(Modifier.width(12.dp))
                Tx(text = s, size = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, ellipsis = true)
                Tx(text = "›", color = Miu.textHint, size = 20.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ==================== 消息列表 ====================
@Composable
private fun MessageList(modifier: Modifier = Modifier, sourceDialog: (SourceData?) -> Unit) {
    val listState = rememberLazyListState()
    val msgCount = ChatModel.msgs.size
    // 读输入法高度（IME inset）：键盘弹出/收起都会变化并触发重组
    val imePx = WindowInsets.ime.getBottom(LocalDensity.current)

    LaunchedEffect(msgCount, ChatModel.typingTick, ChatModel.showRating, ChatModel.humanStatus) {
        var target = msgCount - 1
        if (ChatModel.showRating && ChatModel.humanStatus == HumanStatus.Idle) target = msgCount
        if (target >= 0) listState.scrollToItem(target)
    }

    // 输入法弹出会把可视区压小，若滚动位置停在半空，最新消息会被键盘盖住、需手动上滑才可见。
    // ime 从 0 变非 0 时触发：等键盘动画稳定（180ms）后滚到列表最底。
    LaunchedEffect(imePx) {
        if (imePx > 0 && ChatModel.msgs.isNotEmpty()) {
            delay(180)
            val last = if (ChatModel.showRating && ChatModel.humanStatus == HumanStatus.Idle)
                ChatModel.msgs.size else ChatModel.msgs.size - 1
            if (last >= 0) listState.scrollToItem(last)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(ChatModel.msgs) { _, m ->
            MsgBubble(m, sourceDialog = sourceDialog)
        }
        if (ChatModel.showRating && ChatModel.humanStatus == HumanStatus.Idle) {
            item(key = "rating") { RatingCard() }
        }
    }
}

@Composable
private fun MsgBubble(m: com.rag.customer.state.ChatMsg, sourceDialog: (SourceData?) -> Unit) {
    when (m.role) {
        MsgRole.USER -> Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(0.78f),
                horizontalAlignment = Alignment.End,
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(Miu.userBubble))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    Tx(text = m.content, color = Color.White, size = 15.sp, lineHeight = 22.sp)
                }
                m.time?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Tx(text = it, color = Miu.textHint, size = 11.sp)
                }
            }
        }

        MsgRole.AGENT -> Row(modifier = Modifier.fillMaxWidth()) {
            AgentCol(m, sourceDialog = sourceDialog)
        }

        MsgRole.AI -> Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Miu.card)
                    .padding(horizontal = 15.dp, vertical = 12.dp),
            ) {
                AiMeta(m)
                if (m.streaming && m.content.isEmpty()) {
                    ThinkingDots()
                } else {
                    MdText(
                        text = stripReferenceTail(m.content),
                        textColor = Miu.textMain,
                    )
                }
                SourceChips(m, sourceDialog = sourceDialog)
            }
            m.time?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Tx(text = it, color = Miu.textHint, size = 11.sp)
            }
        }
    }
}

@Composable
private fun AgentCol(m: com.rag.customer.state.ChatMsg, sourceDialog: (SourceData?) -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE6E6E6)),
                    contentAlignment = Alignment.Center,
                ) {
                    Tx(text = "客", color = Miu.textSecondary, size = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.fillMaxWidth(0.8f)) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFFEFF4FF))
                            .padding(horizontal = 15.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Tx(text = "人工客服", color = Color(0xFF3482FF), size = 11.sp, bold = true)
                        }
                        Spacer(Modifier.height(4.dp))
                        MdText(text = m.content, textColor = Miu.textMain)
                    }
                }
            }
            m.time?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Tx(
                    text = it,
                    color = Miu.textHint,
                    size = 11.sp,
                    modifier = Modifier.padding(start = 38.dp),
                )
            }
        }
    }
}

@Composable
private fun AiMeta(m: com.rag.customer.state.ChatMsg) {
    val tags = ArrayList<String>()
    m.intent?.let { tags.add("意图 · $it") }
    if (m.fromCache) tags.add("缓存")
    if (tags.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { t ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Miu.primarySoft)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Tx(text = t, color = Color(0xFF3482FF), size = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SourceChips(m: com.rag.customer.state.ChatMsg, sourceDialog: (SourceData?) -> Unit) {
    val srcs = m.sources?.takeIf { !m.streaming && !m.error && it.isNotEmpty() } ?: return
    Spacer(Modifier.height(10.dp))
    Tx(text = "参考来源", color = Miu.textSecondary, size = 12.sp, bold = true)
    Spacer(Modifier.height(6.dp))
    srcs.forEach { s ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRipple { sourceDialog(s) }
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFF5F7FB))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tx(text = "📄", size = 14.sp)
            Spacer(Modifier.width(8.dp))
            Tx(
                text = s.title ?: "未知资料",
                color = Miu.textMain,
                size = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                ellipsis = true,
            )
            Spacer(Modifier.width(8.dp))
            s.category?.let {
                Tx(text = it, color = Miu.textSecondary, size = 11.sp)
            }
            Spacer(Modifier.width(6.dp))
            Tx(
                text = "相似 ${s.scorePercent}%",
                color = Color(0xFF3482FF),
                size = 11.sp,
                bold = true,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinkingDots")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "dotA",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Miu.textSecondary.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun RatingCard() {
    val stars = ChatModel.rating
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Miu.card)
            .padding(vertical = 16.dp, horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Tx(text = "感谢使用人工客服，请为本次服务打分", color = Miu.textSecondary, size = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row {
            repeat(5) { i ->
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .noRipple { ChatModel.rate(i + 1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Tx(
                        text = "★",
                        color = if (i < stars) Miu.star else Color(0xFFDCDCDC),
                        size = 30.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Tx(text = if (stars > 0) "已提交 ${stars} 星" else "点击星星评分", color = Miu.textHint, size = 12.sp)
    }
}

// ==================== 转人工 / 排队 / 结束条 ====================
@Composable
private fun HumanBar() {
    when (ChatModel.humanStatus) {
        HumanStatus.Queuing -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFF8E6))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tx(text = "⏳", size = 15.sp)
            Spacer(Modifier.width(8.dp))
            Tx(
                text = "已转人工，前方排队 ${ChatModel.queuePos} 人…",
                color = Color(0xFF9A6A00),
                size = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Tx(
                text = "取消排队",
                color = Color(0xFF9A6A00),
                size = 13.sp,
                bold = true,
                modifier = Modifier.noRipple { ChatModel.exitHuman() },
            )
        }

        HumanStatus.Connected -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFEAF2FF))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tx(text = "🧑‍💼", size = 15.sp)
            Spacer(Modifier.width(8.dp))
            Tx(text = "与人工客服对话中", color = Color(0xFF3482FF), size = 13.sp, modifier = Modifier.weight(1f))
            Tx(
                text = "结束会话",
                color = Color(0xFF3482FF),
                size = 13.sp,
                bold = true,
                modifier = Modifier.noRipple { ChatModel.exitHuman() },
            )
        }

        // 空闲态左工具条：转人工客服始终展示（首页亦可见，镜像 Web 端「转人工」入口）；
        // 「首页」胶囊仅在已有消息时出现（回到欢迎页）。
        HumanStatus.Idle -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Miu.pageBg)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ChatModel.msgs.isNotEmpty()) {
                    ActionChip("🏠", "首页", Color(0xFFEFF1F5), Miu.textMain) { ChatModel.goHome() }
                }
                // 历史回放态不显示转人工入口：回放的是已结束会话，转人工无意义
                if (ChatModel.replayConvId == null) {
                    ActionChip(
                        if (Session.isLoggedIn) "👤" else "🔒",
                        if (Session.isLoggedIn) "转人工客服" else "登录后转人工客服",
                        Color(0xFFEAF2FF),
                        Color(0xFF3482FF),
                    ) {
                        if (Session.isLoggedIn) {
                            if (ChatModel.asking) ToastHost.show("请先等待当前回答完成")
                            else ChatModel.transferToHuman()
                        } else {
                            ChatModel.wantsTransfer()
                        }
                    }
                }
            }
        }
    }
}

/** 空闲态快捷操作小胶囊（左对齐工具条用）。 */
@Composable
private fun ActionChip(
    glyph: String,
    label: String,
    bg: Color,
    fg: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .noRipple(onClick = onClick)
            .clip(RoundedCornerShape(15.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tx(text = glyph, size = 12.sp)
            Spacer(Modifier.width(5.dp))
            Tx(text = label, color = fg, size = 13.sp, weight = FontWeight.Medium)
        }
    }
}

// ==================== 回答正文预处理 ====================
/**
 * 掐掉机器人回答末尾的「参考文件：…」尾注。原因：
 * ① 参考文件只展示来源链接意义不大，来源另有底部「参考来源」卡片；
 * ② 知识片段小标题里若含 | 分隔符，会让 markdown 表格解析误判（历史 bug3 大蓝块的根源）。
 */
private fun stripReferenceTail(text: String): String {
    if (text.isEmpty()) return text
    val lines = text.split("\n")
    val idx = lines.indexOfFirst { it.trimStart().startsWith("参考文件") }
    if (idx < 0) return text
    return lines.subList(0, idx).joinToString("\n").trimEnd().ifEmpty { text }
}

// ==================== 输入区 ====================
@Composable
private fun InputPanel(value: String, onValueChange: (String) -> Unit, onSend: (String) -> Unit) {
    val queuing = ChatModel.humanStatus == HumanStatus.Queuing
    val hint = when (ChatModel.humanStatus) {
        HumanStatus.Connected -> "给人工客服留言…"
        HumanStatus.Queuing -> "排队中，暂不能发送…"
        else -> "请输入问题…"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Miu.card)
            .navigationBarsPadding()
            .imePadding()
            // 键盘弹出后：imePadding 把整条输入区抬到输入法之上，
            // 底部 12dp 白底让输入框灰色线框与输入法边界之间留出白色空隙。
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Miu.fieldBg),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !queuing,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Miu.textMain,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Miu.primary),
                    singleLine = true,
                    maxLines = 1,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { onSend(value) },
                    ),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .height(46.dp)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (value.isEmpty()) {
                                Tx(text = hint, color = Miu.textHint, size = 15.sp)
                            }
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            val canSend = value.isNotBlank() && !queuing
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .noRipple(enabled = canSend) { onSend(value) }
                    .clip(CircleShape)
                    .background(
                        if (canSend) SolidColor(Miu.primary)
                        else SolidColor(Color(0xFFDCDCDC)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Tx(text = "↑", color = Color.White, size = 20.sp, weight = FontWeight.Bold)
            }
        }
    }
}


// ==================== 来源详情弹窗 ====================
@Composable
private fun SourceDialog(src: SourceData, onClose: () -> Unit) {
    DialogSheet(
        visible = true,
        onDismiss = onClose,
        title = src.title ?: "参考资料",
    ) {
        val scoreText = if (src.score != null) "相似度 ${src.scorePercent}%" else ""
        src.category?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tx(text = it, color = Miu.textSecondary, size = 12.sp)
                if (scoreText.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Tx(text = scoreText, color = Color(0xFF3482FF), size = 12.sp, bold = true)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        val body = src.content?.takeIf { it.isNotBlank() } ?: "（该片段未附带正文预览）"
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            MdText(text = body, textColor = Miu.textMain)
        }
        Spacer(Modifier.height(6.dp))
        DialogActions(primary = "关闭", onClickPrimary = onClose)
    }
}
