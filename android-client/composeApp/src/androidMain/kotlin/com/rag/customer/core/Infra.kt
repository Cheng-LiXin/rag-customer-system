package com.rag.customer.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ==================== 时间显示 ====================
private val HM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MD_HM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val YMD_HM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 聊天时间文案（类似微信）：今天 → "HH:mm"；昨天 → "昨天 HH:mm"；
 * 今年更早 → "MM-dd HH:mm"；跨年 → "yyyy-MM-dd HH:mm"。
 * 兼容后端两种时间格式：ISO `2026-09-08T21:33:12.9041801` 与空格分隔 `2026-09-08 10:24:12`。
 */
fun chatTimeText(raw: String?): String {
    val s = raw?.takeIf { it.isNotBlank() } ?: return ""
    val dt = runCatching { LocalDateTime.parse(s.replace(' ', 'T')) }.getOrNull() ?: return ""
    val today = LocalDate.now()
    return when (dt.toLocalDate()) {
        today -> dt.format(HM_FMT)
        today.minusDays(1) -> "昨天 " + dt.format(HM_FMT)
        else -> if (dt.year == today.year) dt.format(MD_HM_FMT) else dt.format(YMD_HM_FMT)
    }
}

/** 全局应用上下文：在 MainActivity 里注入，供 SharedPreferences / OkHttp 使用。 */
object AppCtx {
    lateinit var app: Context
}

/** 轻量键值存储（SharedPreferences 封装）。 */
object Prefs {
    private val sp get() = AppCtx.app.getSharedPreferences("rag_customer", Context.MODE_PRIVATE)

    fun str(key: String): String? = sp.getString(key, null)
    fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
    fun del(key: String) { sp.edit().remove(key).apply() }
    fun longOf(key: String): Long? = str(key)?.toLongOrNull()

    fun clearAuth() {
        sp.edit()
            .remove(Session.KEY_TOKEN)
            .remove(Session.KEY_USERNAME)
            .remove(Session.KEY_CONV)
            .remove(Session.KEY_REOPEN)
            .remove(Session.KEY_PENDING)
            .apply()
    }
}

/** 全局轻提示（App 根层渲染，自动消失）。 */
object ToastHost {
    var text by mutableStateOf<String?>(null)
    var serial by mutableIntStateOf(0)
    fun show(msg: String) { text = msg; serial++ }
    fun hide() { text = null }
}

/** 把任意代码切回主线程执行（OkHttp / WebSocket 回调非主线程）。 */
object MainThread {
    private val handler = Handler(Looper.getMainLooper())
    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }
}

/** 根层 Toast 浮层。 */
@Composable
fun ToastLayer() {
    val t = ToastHost.text
    val s = ToastHost.serial
    LaunchedEffect(s) {
        if (t != null) {
            delay(2200)
            ToastHost.hide()
        }
    }
    if (t != null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(top = 14.dp, start = 40.dp, end = 40.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xCC2B2F3A))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                BasicText(
                    text = t,
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                )
            }
        }
    }
}
