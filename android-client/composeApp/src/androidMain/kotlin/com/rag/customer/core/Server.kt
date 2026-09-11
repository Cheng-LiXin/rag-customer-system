package com.rag.customer.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 后端服务器地址（可在设置页改，存 SharedPreferences）。
 *
 * 默认 `http://127.0.0.1:8080` —— 真机 USB 联调走 adb 反向端口映射：
 *   adb reverse tcp:8080 tcp:8080
 * 把手机的 127.0.0.1:8080 转发到电脑的 8080，无需同一 Wi-Fi（手机走移动数据也可）。
 * 注意：拔插 USB / 重启 adb 后需重新执行该命令（可用 `android-client/adb-reverse.ps1`）。
 * 若改用模拟器，把 DEFAULT 换回 `http://10.0.2.2:8080`；
 * 若真机与电脑在同一 Wi-Fi，也可在设置页填电脑局域网 IP（如 http://10.80.32.136:8080）。
 */
object Server {
    private const val KEY = "rag_base_url"
    const val DEFAULT = "http://127.0.0.1:8080"

    var baseUrl by mutableStateOf(Prefs.str(KEY) ?: DEFAULT)
        private set

    /** API 根路径。 */
    val apiRoot: String get() = baseUrl + "/api"

    /** WebSocket 地址（http -> ws）。 */
    val wsUrl: String get() = baseUrl.replaceFirst("http", "ws") + "/ws/customer-service"

    fun set(v: String) {
        val s = v.trim().trimEnd('/')
        if (s.isNotEmpty()) {
            baseUrl = s
            Prefs.put(KEY, s)
            Session.convId = null // 换后端后旧会话游标失效
        }
    }
}
