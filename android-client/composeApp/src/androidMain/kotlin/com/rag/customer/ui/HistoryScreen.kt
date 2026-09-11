package com.rag.customer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rag.customer.core.Router
import com.rag.customer.core.Session
import com.rag.customer.core.ToastHost
import com.rag.customer.data.Api
import com.rag.customer.data.ConversationData
import kotlinx.coroutines.launch

/** 历史对话（需登录）：分页拉取会话 → 点击回首页回放消息（mp history 语义）。 */
@Composable
fun HistoryScreen() {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<ConversationData>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }

    fun loadPage(p: Int) {
        scope.launch {
            if (p == 1) loading = true else loadingMore = true
            try {
                val res = Api.history(p, 20)
                total = res.total
                records = if (p == 1) res.records else records + res.records
                page = p
            } catch (e: Exception) {
                ToastHost.show(e.message ?: "加载失败")
            } finally {
                loading = false
                loadingMore = false
                loadedOnce = true
            }
        }
    }

    if (!Session.isLoggedIn) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Miu.pageBg),
        ) {
            TopBar(title = "历史对话", subtitle = "登录用户专属 · 点按回放", onBack = { Router.pop() })
            LoginGate("登录后即可查看你的历史对话记录")
        }
        return
    }

    LaunchedEffect(Unit) { if (!loadedOnce) loadPage(1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Miu.pageBg),
    ) {
        TopBar(title = "历史对话", subtitle = "登录用户专属 · 点按回放", onBack = { Router.pop() })

        when {
            loading && records.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Tx(text = "加载中…", color = Miu.textSecondary, size = 14.sp)
            }

            records.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Tx(text = "🕘", size = 44.sp)
                Spacer(Modifier.height(12.dp))
                Tx(text = "暂无历史对话", size = 16.sp, bold = true)
                Spacer(Modifier.height(6.dp))
                Tx(text = "回首页开始第一次咨询吧", color = Miu.textSecondary, size = 13.sp)
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp),
            ) {
                Tx(
                    text = "共 $total 条记录",
                    color = Miu.textSecondary,
                    size = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp,
                    ),
                ) {
                    items(records, key = { it.id }) { c ->
                        HistoryRow(c) {
                            Session.reopenConvId = c.id
                            Router.backToChat()
                        }
                    }
                    if (records.size < total) {
                        item(key = "load-more") {
                            Box(modifier = Modifier.padding(horizontal = 40.dp)) {
                                SoftButton(
                                    text = if (loadingMore) "加载中…" else "加载更多",
                                    onClick = { loadPage(page + 1) },
                                    enabled = !loadingMore,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(c: ConversationData, onClick: () -> Unit) {
    val human = c.isHuman
    val glyph = if (human) "🧑" else "🤖"
    val tileBg = if (human) Color(0xFFEAF2FF) else Miu.primarySoft
    val title = c.title?.takeIf { it.isNotBlank() }
        ?: if (human) "人工客服会话" else "智能问答"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noRipple(onClick = onClick)
            .clip(RoundedCornerShape(20.dp))
            .background(Miu.card)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tileBg),
            contentAlignment = Alignment.Center,
        ) {
            Tx(text = glyph, size = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tx(text = title, size = 15.sp, bold = true, maxLines = 1, ellipsis = true)
                if (c.isLive) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF34C759)),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Tx(
                text = if (human) "人工客服 · " + Dates.short(c.updateTime)
                else "智能问答 · " + Dates.short(c.updateTime),
                color = Miu.textSecondary,
                size = 12.sp,
            )
        }
        Spacer(Modifier.width(6.dp))
        Chevron()
    }
}
