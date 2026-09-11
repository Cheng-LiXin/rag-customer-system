package com.rag.customer.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rag.customer.core.MainThread
import com.rag.customer.core.Router
import com.rag.customer.core.Route
import com.rag.customer.core.Session
import com.rag.customer.core.ToastHost
import com.rag.customer.core.chatTimeText
import com.rag.customer.data.Api
import com.rag.customer.data.JsonCfg
import com.rag.customer.data.SourceData
import com.rag.customer.net.WsHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime

enum class MsgRole { USER, AI, AGENT }

enum class HumanStatus { Idle, Queuing, Connected }

data class ChatMsg(
    val id: Int,
    val role: MsgRole,
    val content: String = "",
    val streaming: Boolean = false,
    val fromCache: Boolean = false,
    val sources: List<SourceData>? = null,
    val intent: String? = null,
    val error: Boolean = false,
    /** 显示用时间文案（今天 HH:mm / 昨天 HH:mm / MM-dd HH:mm）；气泡下方展示。 */
    val time: String? = null,
)

/**
 * 问答 / 人工会话状态机（Android 端全局单例，等价 mp index.vue 的全部脚本逻辑）。
 * 消息列表、人工状态、会话游标、WS 均由这里驱动。
 */
object ChatModel {
    val msgs = mutableStateListOf<ChatMsg>()

    var asking by mutableStateOf(false)          // AI 问答请求/打字机进行中
    var humanStatus by mutableStateOf(HumanStatus.Idle)
    var humanConvId by mutableStateOf<Long?>(null)
    var queuePos by mutableIntStateOf(0)
    var rating by mutableIntStateOf(0)
    var showRating by mutableStateOf(false)      // 人工会话结束后展示满意度评价卡
    var ratingConvId by mutableStateOf<Long?>(null) // 供评价提交保留会话 id
    var typingTick by mutableIntStateOf(0)       // 打字机每帧 +1，供列表自动滚动锚定
    /** 当前正在回放的历史会话 id（非 null = 历史回放态：隐藏「转人工客服」入口）。 */
    var replayConvId by mutableStateOf<Long?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var typerJob: Job? = null
    private var nextId = 0

    private fun newId(): Int = ++nextId

    private fun push(role: MsgRole, content: String, extra: ChatMsg = ChatMsg(0, role)): Int {
        val id = newId()
        val time = extra.time?.takeIf { it.isNotBlank() } ?: chatTimeText(LocalDateTime.now().toString())
        msgs.add(extra.copy(id = id, role = role, content = content, time = time))
        return id
    }

    private fun update(id: Int, transform: (ChatMsg) -> ChatMsg) {
        val idx = msgs.indexOfFirst { it.id == id }
        if (idx >= 0) msgs[idx] = transform(msgs[idx])
    }

    fun aiStreamingId(): Int = msgs.lastOrNull { it.role == MsgRole.AI && it.streaming }?.id ?: -1

    fun lastMsgContent(): String? = msgs.lastOrNull()?.content

    fun isTyping(): Boolean = msgs.any { it.streaming }

    // ==================== 问答 ====================
    /** @return 是否真正把消息送出去（false = 空白/忙/排队中，输入应保留，让用户稍后重试） */
    fun send(raw: String): Boolean {
        val content = raw.trim()
        if (content.isEmpty()) return false
        if (humanStatus == HumanStatus.Connected) {
            sendHuman(content)
            return true
        }
        if (asking) {
            ToastHost.show("正在回答，请稍候…")
            return false
        }
        if (humanStatus == HumanStatus.Queuing) {
            ToastHost.show("排队中，请先等待客服接入")
            return false
        }
        showRating = false
        replayConvId = null // 历史回放中发新消息 = 回到实时会话，恢复转人工入口
        push(MsgRole.USER, content)
        asking = true
        val aiId = push(MsgRole.AI, "", ChatMsg(0, MsgRole.AI, streaming = true))
        scope.launch {
            try {
                val res = Api.ask(content, Session.convId)
                Session.convId = res.conversationId ?: Session.convId
                typewrite(aiId, res.answer, res.fromCache, res.sources, res.intentCategory)
            } catch (e: Exception) {
                val fallback = e.message?.takeIf { it.isNotBlank() } ?: "（回答失败，请稍后重试）"
                update(aiId) { it.copy(content = fallback, streaming = false, error = true) }
            } finally {
                asking = false
            }
        }
        return true
    }

    /** 打字机：~80 帧播完（镜像 mp typewrite），完成后落格式与元信息。 */
    private fun typewrite(
        id: Int,
        full: String,
        fromCache: Boolean,
        sources: List<SourceData>?,
        intent: String?,
    ) {
        typerJob?.cancel()
        typerJob = scope.launch {
            val step = maxOf(1, (full.length + 79) / 80)
            var i = 0
            while (i < full.length) {
                delay(24)
                i = minOf(full.length, i + step)
                update(id) { it.copy(content = full.substring(0, i), streaming = true) }
                typingTick++
            }
            update(id) {
                it.copy(
                    content = full,
                    streaming = false,
                    fromCache = fromCache,
                    sources = sources,
                    intent = intent,
                    error = false,
                )
            }
            typingTick++
        }
    }

    /** 回首页：清消息与人工态，保留会话游标（mp goHome 语义）。 */
    fun goHome() {
        typerJob?.cancel()
        WsHub.disconnect()
        msgs.clear()
        replayConvId = null
        humanStatus = HumanStatus.Idle
        humanConvId = null
        queuePos = 0
        rating = 0
        showRating = false
        ratingConvId = null
    }

    // ==================== 转人工（WS） ====================
    fun wantsTransfer(): Boolean {
        if (humanStatus != HumanStatus.Idle) return false
        if (!Session.isLoggedIn) {
            Session.pendingTransfer = true
            ToastHost.show("转人工客服需要登录")
            Router.push(Route.Login)
            return false
        }
        return true
    }

    fun transferToHuman() {
        if (humanStatus != HumanStatus.Idle || !Session.isLoggedIn) return
        showRating = false
        ToastHost.show("正在转接…")
        scope.launch {
            try {
                val lastUser = msgs.lastOrNull { it.role == MsgRole.USER }?.content?.take(50)
                val res = Api.transfer(Session.convId, lastUser)
                Session.convId = res.conversationId
                humanConvId = res.conversationId
                openHumanSocket(res.conversationId)
                if (res.assigned) {
                    humanStatus = HumanStatus.Connected
                    push(MsgRole.AI, "已接入人工客服，请描述您的问题。")
                } else {
                    humanStatus = HumanStatus.Queuing
                    queuePos = res.queuePosition
                    push(MsgRole.AI, "已进入排队，前方 ${res.queuePosition} 位用户，请稍候…")
                }
            } catch (e: Exception) {
                ToastHost.show(e.message ?: "转接人工失败")
            }
        }
    }

    private fun openHumanSocket(convId: Long) {
        val register = buildString {
            append("{\"type\":\"register\",\"role\":\"user\",\"conversationId\":")
            append(convId)
            append("}")
        }
        WsHub.connect(register, object : WsHub.Listener {
            override fun onOpen() {}

            override fun onMessage(text: String) {
                handleWsFrame(text)
            }

            override fun onClosedUnexpectedly() {
                if (humanStatus == HumanStatus.Connected) {
                    push(MsgRole.AI, "客服已结束本次会话，感谢您的咨询。")
                }
                humanStatus = HumanStatus.Idle
                showRating = false
            }
        })
    }

    private fun handleWsFrame(text: String) {
        val obj = runCatching { JsonCfg.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
        when (type) {
            "message" -> {
                if (obj["senderType"]?.jsonPrimitive?.contentOrNull == "AGENT") {
                    humanStatus = HumanStatus.Connected
                    queuePos = 0
                    push(MsgRole.AGENT, obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
            }
            "assigned" -> {
                humanStatus = HumanStatus.Connected
                queuePos = 0
            }
            "position" -> {
                humanStatus = HumanStatus.Queuing
                queuePos = obj["position"]?.jsonPrimitive?.intOrNull ?: 0
            }
            "closed" -> {
                val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
                val wasConnected = humanStatus == HumanStatus.Connected
                handleClosed(reason, wasConnected)
            }
        }
    }

    private fun handleClosed(reason: String?, wasConnected: Boolean) {
        if (humanStatus != HumanStatus.Idle) {
            push(
                MsgRole.AI,
                if (reason == "timeout") "长时间未对话，本次会话已自动结束，感谢您的咨询。"
                else "客服已结束本次会话，感谢您的咨询。",
            )
        }
        ratingConvId = humanConvId
        humanStatus = HumanStatus.Idle
        humanConvId = null
        if (wasConnected) showRating = true
    }

    fun sendHuman(content: String) {
        val convId = humanConvId ?: return
        if (humanStatus != HumanStatus.Connected) {
            ToastHost.show("客服连接已断开，请重新转接")
            return
        }
        push(MsgRole.USER, content)
        val body = "{\"type\":\"message\",\"conversationId\":$convId,\"content\":${JsonPrimitive(content)}}"
        WsHub.send(body)
    }

    fun rate(val_: Int) {
        rating = val_
        showRating = false
        val convId = ratingConvId ?: humanConvId
        if (convId == null) return
        scope.launch {
            try {
                Api.submitSatisfaction(convId, val_)
                ToastHost.show("感谢您的评价！")
            } catch (e: Exception) {
                // 静默：评价失败不打扰
            } finally {
                ratingConvId = null
            }
        }
    }

    fun exitHuman() {
        val convId = humanConvId
        if (convId != null) {
            scope.launch {
                runCatching { Api.closeConversation(convId) }
                MainThread.post {
                    afterExitHuman()
                }
            }
        } else {
            afterExitHuman()
        }
    }

    private fun afterExitHuman() {
        typerJob?.cancel()
        WsHub.disconnect()
        humanStatus = HumanStatus.Idle
        humanConvId = null
        queuePos = 0
        rating = 0
        showRating = false
        ratingConvId = null
        replayConvId = null
        msgs.clear()
    }

    /** 加载指定会话的历史消息回放（从历史会话进入）。 */
    fun replayConversation(convId: Long) {
        scope.launch {
            try {
                val list = Api.historyMessages(convId)
                msgs.clear()
                nextId = 0
                for (m in list) {
                    val role = when (m.senderType) {
                        "AI" -> MsgRole.AI
                        "AGENT" -> MsgRole.AGENT
                        else -> MsgRole.USER
                    }
                    push(role, m.content, ChatMsg(0, role, time = chatTimeText(m.createTime)))
                }
                replayConvId = convId // 历史回放态：隐藏「转人工客服」（会话已结束）
            } catch (e: Exception) {
                ToastHost.show(e.message ?: "加载会话失败")
            }
        }
    }

    /** 登录成功后待办：pending 转人工引导。 */
    fun onLoginReturned() {
        if (Session.pendingTransfer && Session.isLoggedIn) {
            Session.pendingTransfer = false
            transferToHuman()
        }
    }

    /** 退出登录：重置会话全部状态（含会话游标）。 */
    fun resetForLogout() {
        typerJob?.cancel()
        WsHub.disconnect()
        msgs.clear()
        humanStatus = HumanStatus.Idle
        humanConvId = null
        queuePos = 0
        rating = 0
        showRating = false
        ratingConvId = null
        asking = false
        replayConvId = null
        Session.logout()
    }
}
