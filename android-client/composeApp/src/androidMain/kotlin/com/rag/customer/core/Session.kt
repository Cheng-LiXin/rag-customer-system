package com.rag.customer.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rag.customer.data.MeData

/**
 * 登录态 + 会话游标（Android 端全局单例，等价 mp 的 stores/auth.ts + 部分 utils/config）。
 * token / username 持久化到 SharedPreferences；profile 仅内存缓存（冷启动走 /auth/me 拉取）。
 */
object Session {
    const val KEY_TOKEN = "rag_token"
    const val KEY_USERNAME = "rag_username"
    const val KEY_CONV = "rag_conv_id"
    const val KEY_REOPEN = "rag_reopen_conv"
    const val KEY_PENDING = "rag_pending_transfer"

    var token by mutableStateOf(Prefs.str(KEY_TOKEN).orEmpty())
    var username by mutableStateOf(Prefs.str(KEY_USERNAME).orEmpty())
    var profile by mutableStateOf<MeData?>(null)

    val isLoggedIn: Boolean get() = token.isNotEmpty()
    val roles: List<String> get() = profile?.roles ?: emptyList()
    val isStaff: Boolean get() = roles.any { it == "ROLE_AGENT" || it == "ROLE_ADMIN" }
    val displayName: String
        get() = profile?.nickname?.takeIf { it.isNotBlank() }
            ?: profile?.username
            ?: username.ifEmpty { "访客" }

    // 说明：用「私有 State 持有 + 自定义 getter/setter」而非 `by mutableStateOf` 搭配手写
    // setXxx 函数——后者会让 delegate 生成 setConvId/setPendingTransfer/setReopen 的 JVM
    // setter 与手写函数撞签名（Platform declaration clash）。持久化直接内联进 setter。

    /** 会话游标：当前问答会话 id（问答 / 转人工复用）。登录、登出、开新会话时更新。 */
    private val _convId = mutableStateOf(Prefs.longOf(KEY_CONV))
    var convId: Long?
        get() = _convId.value
        set(v) {
            _convId.value = v
            if (v != null) Prefs.put(KEY_CONV, v.toString()) else Prefs.del(KEY_CONV)
        }

    /** 转人工引导：游客点转人工 → 置标记 → 登录后自动转。 */
    private val _pendingTransfer = mutableStateOf(Prefs.str(KEY_PENDING) != null)
    var pendingTransfer: Boolean
        get() = _pendingTransfer.value
        set(b) {
            _pendingTransfer.value = b
            if (b) Prefs.put(KEY_PENDING, "1") else Prefs.del(KEY_PENDING)
        }

    /** 历史会话回放游标：History 点某会话 → 置标记 → 回到问答页加载消息。 */
    private val _reopenConvId = mutableStateOf(Prefs.longOf(KEY_REOPEN))
    var reopenConvId: Long?
        get() = _reopenConvId.value
        set(v) {
            _reopenConvId.value = v
            if (v != null) Prefs.put(KEY_REOPEN, v.toString()) else Prefs.del(KEY_REOPEN)
        }

    fun saveLogin(tok: String, user: String, p: MeData?) {
        token = tok
        username = user
        profile = p
        Prefs.put(KEY_TOKEN, tok)
        Prefs.put(KEY_USERNAME, user)
    }

    fun updateProfile(p: MeData?) { profile = p }

    fun expire() {
        Prefs.clearAuth()
        token = ""
        username = ""
        profile = null
        convId = null
        pendingTransfer = false
        reopenConvId = null
    }

    /** 登出（保留对话游标语义：mp 登出会清 conv，这里保持一致）。 */
    fun logout() {
        expire()
    }
}

/** 鉴权失效钩子：由数据层在 HTTP 401/403 时触发，UI 层注册处理（清态 + 回登录页）。 */
object AuthExpiry {
    var onExpire: (() -> Unit)? = null
}
