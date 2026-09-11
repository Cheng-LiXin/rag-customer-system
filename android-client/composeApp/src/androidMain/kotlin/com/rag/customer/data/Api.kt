package com.rag.customer.data

import com.rag.customer.core.AuthExpiry
import com.rag.customer.core.Server
import com.rag.customer.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 业务错误（HTTP 200 + code!=200，或后端 message 兜底）。message 直接上浮给用户。 */
class ApiException(val code: Int, override val message: String) : Exception(message)

/** 网络/服务器不可达。 */
class NetException(override val message: String) : Exception(message)

/**
 * 后端 HTTP 客户端（OkHttp，镜像 mp request.ts 语义）：
 *  - 自动带 Authorization: Bearer <token>
 *  - 响应形如 {code,message,data}，code==200 解析 data；否则抛 ApiException(message)
 *  - 少数接口（历史消息）直接返回裸 JSON 数组 → 无 code 时整体视为 data
 *  - HTTP 401/403：清登录态 + 触发 AuthExpiry（UI 回登录页）
 */
object Api {
    private val json get() = JsonCfg.json

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MT = "application/json; charset=utf-8".toMediaType()

    /** 请求头：Auth header + JSON Content-Type。 */
    private fun buildRequest(
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: String? = null,
    ): Request {
        val url = Server.apiRoot + path
        val httpUrl = url.toHttpUrl()
        val builder = httpUrl.newBuilder()
        query.forEach { (k, v) ->
            if (v != null) builder.addQueryParameter(k, v.toString())
        }
        val reqBuilder = Request.Builder().url(builder.build())
        if (Session.token.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer ${Session.token}")
        }
        when (method) {
            "POST" -> reqBuilder.post((body ?: "").toRequestBody(JSON_MT))
            "PUT" -> reqBuilder.put((body ?: "").toRequestBody(JSON_MT))
            else -> reqBuilder.get()
        }
        return reqBuilder.build()
    }

    private class Envelope(val code: Int, val message: String, val data: JsonElement?)

    private fun parseEnvelope(raw: String): Envelope {
        val el = json.parseToJsonElement(raw)
        val obj = (el as? JsonObject)
        if (obj != null && obj.containsKey("code")) {
            val code = obj["code"]?.jsonPrimitive?.intOrNull ?: -1
            val msg = obj["message"]?.jsonPrimitive?.contentOrNull ?: ""
            return Envelope(code, msg, obj["data"])
        }
        return Envelope(200, "", el)
    }

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: String? = null,
    ): Envelope {
        val req = buildRequest(method, path, query, body)
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val http = resp.code
                    if (http in 200..299) {
                        parseEnvelope(raw)
                    } else {
                        if (http == 401 || http == 403) {
                            handleAuthExpired(raw)
                        }
                        // 后端兜底 message
                        val env = try { parseEnvelope(raw) } catch (e: Exception) { Envelope(http, "请求失败($http)", null) }
                        throw ApiException(http, env.message.ifEmpty { "请求失败($http)" })
                    }
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw NetException("网络异常，请确认后端已启动或服务器地址正确")
        }
    }

    private fun handleAuthExpired(raw: String) {
        val msg = try {
            val env = parseEnvelope(raw)
            env.message.ifEmpty { "登录已过期，请重新登录" }
        } catch (e: Exception) {
            "登录已过期，请重新登录"
        }
        Session.expire()
        AuthExpiry.onExpire?.invoke()
        throw ApiException(401, msg)
    }

    private inline fun <reified T> envData(env: Envelope): T {
        if (env.code != 200) throw ApiException(env.code, env.message.ifEmpty { "请求失败" })
        return json.decodeFromJsonElement(serializer(), env.data ?: JsonNull)
    }

    private fun ok(env: Envelope) {
        if (env.code != 200) throw ApiException(env.code, env.message.ifEmpty { "请求失败" })
    }

    // ==================== auth ====================
    suspend fun login(username: String, password: String): LoginData =
        envData(request("POST", "/auth/login", body = dynamicBody(mapOf("username" to username, "password" to password))))

    suspend fun register(phone: String, password: String, confirmPassword: String, nickname: String?): RegisterData =
        envData(request("POST", "/auth/register", body = dynamicBody(mapOf(
            "phone" to phone,
            "password" to password,
            "confirmPassword" to confirmPassword,
            "nickname" to nickname,
        ))))

    suspend fun me(): MeData = envData(request("GET", "/auth/me"))

    suspend fun profile(): ProfileData = envData(request("GET", "/auth/profile"))

    suspend fun updateProfile(fields: Map<String, Any?>): Unit =
        ok(request("PUT", "/auth/profile", body = dynamicBody(fields)))

    suspend fun changePassword(oldPassword: String, newPassword: String): Unit =
        ok(request("PUT", "/auth/password", body = dynamicBody(mapOf("oldPassword" to oldPassword, "newPassword" to newPassword))))

    // ==================== chat ====================
    suspend fun ask(message: String, conversationId: Long?): ChatAnswerData =
        envData(request("POST", "/chat/ask", body = dynamicBody(mapOf(
            "message" to message,
            "conversationId" to conversationId,
        ))))

    data class HistoryPage(val total: Int, val records: List<ConversationData>)

    suspend fun history(pageNum: Int, pageSize: Int): HistoryPage {
        val env = request("GET", "/chat/history", query = mapOf("pageNum" to pageNum, "pageSize" to pageSize))
        if (env.code != 200) throw ApiException(env.code, env.message.ifEmpty { "请求失败" })
        val obj = env.data as? JsonObject
        if (obj == null) return HistoryPage(0, emptyList())
        val total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
        val recordsEl = obj["records"] ?: return HistoryPage(total, emptyList())
        val records = json.decodeFromJsonElement(ListSerializer(ConversationData.serializer()), recordsEl)
        return HistoryPage(total, records)
    }

    suspend fun historyMessages(conversationId: Long): List<MessageData> {
        val env = request("GET", "/chat/history/$conversationId/messages")
        if (env.code != 200) throw ApiException(env.code, env.message.ifEmpty { "请求失败" })
        return if (env.data == null || env.data is JsonNull) emptyList()
        else json.decodeFromJsonElement(ListSerializer(MessageData.serializer()), env.data)
    }

    // ==================== agent / satisfaction ====================
    suspend fun transfer(conversationId: Long?, summary: String?): TransferData =
        envData(request("POST", "/agent/transfer", body = dynamicBody(mapOf(
            "conversationId" to conversationId,
            "summary" to summary,
        ))))

    suspend fun closeConversation(id: Long): Unit =
        ok(request("POST", "/agent/conversation/$id/close"))

    suspend fun submitSatisfaction(conversationId: Long, rating: Int, comment: String? = null): Unit =
        ok(request("POST", "/satisfaction", body = dynamicBody(mapOf(
            "conversationId" to conversationId,
            "rating" to rating,
            "comment" to comment,
        ))))
}
