package com.rag.customer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rag.customer.core.Router
import com.rag.customer.core.Server
import com.rag.customer.core.ToastHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

private val pingClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
}

/** 仅探活：返回 "HTTP x"，网络不通抛 IOException。 */
private suspend fun ping(base: String): String = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(base).build()
    pingClient.newCall(req).execute().use { "HTTP ${it.code}" }
}

/** 服务器设置：真机调试时把地址改成电脑的局域网 IP。 */
@Composable
fun SettingsScreen() {
    var url by remember { mutableStateOf(Server.baseUrl) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Miu.pageBg),
    ) {
        TopBar(title = "服务器设置", subtitle = "地址保存后立即生效", onBack = { Router.pop() })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(16.dp),
            ) {
                Column {
                    Tx(text = "连接说明", size = 15.sp, bold = true)
                    Spacer(Modifier.height(8.dp))
                    Tx(text = "• Android 模拟器访问本机：http://10.0.2.2:8080", color = Miu.textSecondary, size = 13.sp, lineHeight = 20.sp)
                    Tx(text = "• 真机调试：改为电脑的局域网 IP（如 http://192.168.1.100:8080），需与后端同一 Wi-Fi", color = Miu.textSecondary, size = 13.sp, lineHeight = 20.sp)
                    Tx(text = "• 智能问答自动升级为 WebSocket 人工通道，无需单独配置", color = Miu.textSecondary, size = 13.sp, lineHeight = 20.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Field(
                value = url,
                onValueChange = { url = it },
                hint = "http://192.168.1.100:8080",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            FilledButton(
                text = if (saving) "保存中…" else "保存并重连",
                onClick = {
                    val v = url.trim().trimEnd('/')
                    if (v.isEmpty()) {
                        ToastHost.show("地址不能为空")
                        return@FilledButton
                    }
                    saving = true
                    scope.launch {
                        try {
                            ping(v)
                            Server.set(v)
                            ToastHost.show("已保存并连接新服务器")
                        } catch (e: Exception) {
                            // 地址不合法/暂不可达仍允许保存，便于先设后用
                            Server.set(v)
                            ToastHost.show("已保存（当前地址暂未连通，请确认后端已启动）")
                        } finally {
                            saving = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            SoftButton(
                text = "测试当前地址",
                onClick = {
                    val v = url.trim().trimEnd('/')
                    if (v.isEmpty()) {
                        ToastHost.show("地址不能为空")
                        return@SoftButton
                    }
                    scope.launch {
                        try {
                            val code = ping(v)
                            ToastHost.show("连接成功（$code）")
                        } catch (e: IOException) {
                            ToastHost.show("无法连接，请检查地址与后端")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Tx(
                text = "当前地址：${Server.baseUrl}\nWS 通道：${Server.wsUrl}",
                color = Miu.textHint,
                size = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}
