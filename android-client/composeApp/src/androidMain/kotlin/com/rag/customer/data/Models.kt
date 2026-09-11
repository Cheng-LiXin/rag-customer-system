package com.rag.customer.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 后端契约 DTO（camelCase 与 Jackson 默认一致，仅镜像 mp-client/types.ts 使用到的字段）。
 * 所有字段给默认值以容忍缺失；多余字段靠 [JsonCfg].ignoreUnknownKeys 吸收。
 */
object JsonCfg {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
    }
}

// ==================== auth ====================
@Serializable
data class LoginData(val token: String = "", val username: String = "")

@Serializable
data class RegisterData(val id: Long = 0, val username: String = "")

@Serializable
data class MeData(
    val username: String = "",
    val roles: List<String> = emptyList(),
    val id: Long? = null,
    val nickname: String? = null,
    val avatar: String? = null,
)

@Serializable
data class ProfileData(
    val id: Long = 0,
    val username: String = "",
    val nickname: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val province: String? = null,
    val city: String? = null,
    val identity: String? = null,
    val avatar: String? = null,
    val createTime: String? = null,
    val status: Int? = null,
    val roles: List<String> = emptyList(),
    val nicknameUpdatedAt: String? = null,
    val nicknameEditable: Boolean? = null,
    val nicknameCooldownDays: Int? = null,
)

// ==================== chat ====================
@Serializable
data class SourceData(
    val chunkId: JsonElement? = null,
    val title: String? = null,
    val category: String? = null,
    val content: String? = null,
    val score: Double? = null,
) {
    val scorePercent: Int get() = ((score ?: 0.0) * 100).toInt()
}

@Serializable
data class ChatAnswerData(
    val answer: String = "",
    val fromCache: Boolean = false,
    val conversationId: Long? = null,
    val sources: List<SourceData>? = null,
    val intentCategory: String? = null,
)

@Serializable
data class ConversationData(
    val id: Long = 0,
    val userId: Long = 0,
    val userName: String? = null,
    val title: String? = null,
    val sessionType: String? = null,
    val status: Int? = null,
    val agentId: Long? = null,
    val createTime: String? = null,
    val updateTime: String? = null,
) {
    val isHuman: Boolean get() = sessionType == "HUMAN"
    val isLive: Boolean get() = status == 1
}

@Serializable
data class MessageData(
    val id: Long = 0,
    val conversationId: Long = 0,
    val senderType: String = "",
    val content: String = "",
    val citations: String? = null,
    val fromCache: Int? = null,
    val createTime: String? = null,
)

// ==================== agent / satisfaction ====================
@Serializable
data class TransferData(
    val conversationId: Long = 0,
    val queuePosition: Int = 0,
    val assigned: Boolean = false,
)

@Serializable
data class QueueData(val conversationId: Long = 0, val position: Int = 0)

// ==================== 手写请求体工具（供 profile 局部字段等动态 body 用） ====================
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

fun dynamicBody(fields: Map<String, Any?>): String =
    buildJsonObject {
        fields.forEach { (k, v) -> if (v != null) put(k, v.toJsonElement()) }
    }.toString()
