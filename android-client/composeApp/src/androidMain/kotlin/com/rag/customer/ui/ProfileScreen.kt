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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rag.customer.core.Router
import com.rag.customer.core.Route
import com.rag.customer.core.Session
import com.rag.customer.core.ToastHost
import com.rag.customer.data.Api
import com.rag.customer.data.ProfileData
import com.rag.customer.data.RegionData
import com.rag.customer.state.ChatModel
import kotlinx.coroutines.launch

/**
 * 后端 /auth/profile 的 roles 实为 authentication.getAuthorities() —— ROLE_ 前缀角色码 + 权限码混排。
 * 权限码（无 ROLE_ 前缀）不应显示为身份标签，故仅认 ROLE_* 角色；返回 null 表示需过滤。
 */
private fun roleLabel(r: String): String? = when {
    r == "ROLE_ADMIN" -> "管理员"
    r == "ROLE_AGENT" -> "客服"
    r.startsWith("ROLE_") -> "用户"
    else -> null
}

/** 个人中心（需登录）：资料 + 地区身份 + 账号安全（镜像 mp profile）。 */
@Composable
fun ProfileScreen() {
    if (!Session.isLoggedIn) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Miu.pageBg),
        ) {
            TopBar(title = "个人中心", onBack = { Router.pop() })
            LoginGate("登录后完善你的个人资料")
        }
        return
    }

    var pf by remember { mutableStateOf<ProfileData?>(null) }
    LaunchedEffect(Unit) {
        pf = runCatching { Api.profile() }.getOrNull()
            ?: ProfileData(username = Session.username)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Miu.pageBg),
    ) {
        TopBar(title = "个人中心", onBack = { Router.pop() })
        val d = pf
        if (d == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Tx(text = "加载中…", color = Miu.textSecondary, size = 14.sp)
            }
        } else {
            ProfileBody(data = d, onUpdated = { pf = it })
        }
    }
}

@Composable
private fun ProfileBody(data: ProfileData, onUpdated: (ProfileData) -> Unit) {
    val scope = rememberCoroutineScope()

    // 编辑弹层开关
    var nickDialog by remember { mutableStateOf(false) }
    var emailDialog by remember { mutableStateOf(false) }
    var provinceDialog by remember { mutableStateOf(false) }
    var cityDialog by remember { mutableStateOf(false) }
    var identityDialog by remember { mutableStateOf(false) }
    var pwdDialog by remember { mutableStateOf(false) }
    var logoutDialog by remember { mutableStateOf(false) }

    // 通用保存：patch 后端 → 更新本地 → 刷新 Session（昵称即时同步给客服/后台）
    // 注意：后端「昵称变更才限改」，它用「请求里的 nickname 与库内是否一致」判变更。
    // 改地区/邮箱等非昵称字段时若不带 nickname，请求里为 null ≠ 库内昵称 → 被误判成改昵称。
    // 故非昵称保存补发当前昵称（值不变 → 后端跳过限改）；真改昵称时走 fields 里的新值。
    fun patch(mut: (ProfileData) -> ProfileData, fields: Map<String, Any?>, onDone: () -> Unit = {}) {
        scope.launch {
            try {
                val body = if (fields.containsKey("nickname") || data.nickname == null) fields
                else fields + ("nickname" to data.nickname)
                Api.updateProfile(body)
                onUpdated(mut(data))
                runCatching { Api.me() }.getOrNull()?.let { Session.updateProfile(it) }
                ToastHost.show("已保存")
                onDone()
            } catch (e: Exception) {
                ToastHost.show(e.message ?: "保存失败")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            IdentityCard(data)

        ProfileSection("基础资料") {
            InfoRow("昵称", data.nickname?.ifBlank { "未设置" } ?: "未设置") { nickDialog = true }
            InfoRow("手机号 / 账号", data.phone ?: data.username, editable = false) {}
            InfoRow("邮箱", data.email?.ifBlank { "未设置" } ?: "未设置") { emailDialog = true }
        }
        ProfileSection("地区与身份") {
            InfoRow("所在省份", data.province?.takeIf { it.isNotBlank() } ?: "未设置") { provinceDialog = true }
            InfoRow("城市", data.city?.takeIf { it.isNotBlank() } ?: "未设置") {
                if (data.province == null) ToastHost.show("请先选择省份")
                else cityDialog = true
            }
            InfoRow("我的身份", data.identity ?: "未设置") { identityDialog = true }
        }
        ProfileSection("更多") {
            SettingRow("🔑", Color(0xFFFFF3E0), "修改密码", "定期更换更安全") { pwdDialog = true }
            SettingRow("🌐", Miu.primarySoft, "服务器设置", "局域网联调时修改地址") { Router.push(Route.Settings) }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFFFECEA))
                .noRipple { logoutDialog = true }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Tx(
                text = "退出登录",
                color = Miu.danger,
                size = 16.sp,
                weight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(24.dp))
        }

        // ==================== 编辑弹层 ====================
        if (nickDialog) {
        TextEditDialog(
            title = "修改昵称",
            hint = "昵称（30 天内限改一次）",
            initial = data.nickname.orEmpty(),
            onDismiss = { nickDialog = false },
            onSave = { v ->
                val s = v.trim()
                if (s.isEmpty()) return@TextEditDialog "昵称不能为空"
                if (s.length > 20) return@TextEditDialog "昵称过长（≤20 字）"
                patch({ it.copy(nickname = s) }, mapOf("nickname" to s)) { nickDialog = false }
                null
            },
        )
    }
    if (emailDialog) {
        TextEditDialog(
            title = "修改邮箱",
            hint = "邮箱（选填）",
            initial = data.email.orEmpty(),
            onDismiss = { emailDialog = false },
            onSave = { v ->
                val s = v.trim()
                if (s.isNotEmpty() && !s.contains('@')) return@TextEditDialog "邮箱格式不正确"
                // 空串也提交（dynamicBody 丢 null，无法清空字段）；后端兼容存储空串，展示层按空显示「未设置」
                patch({ it.copy(email = s.ifEmpty { null }) }, mapOf("email" to s)) { emailDialog = false }
                null
            },
        )
    }
    if (provinceDialog) {
        OptionsSheet(
            title = "选择省份",
            options = RegionData.provinceNames(),
            selected = data.province,
            onPick = { p ->
                provinceDialog = false
                // 换省清空城市：提交 "" 而非 null（dynamicBody 丢 null）
                patch({ it.copy(province = p, city = "") }, mapOf("province" to p, "city" to ""))
            },
            onClose = { provinceDialog = false },
        )
    }
    if (cityDialog) {
        OptionsSheet(
            title = "选择城市",
            options = RegionData.citiesOf(data.province),
            selected = data.city,
            onPick = { c ->
                cityDialog = false
                patch({ it.copy(city = c) }, mapOf("city" to c))
            },
            onClose = { cityDialog = false },
        )
    }
    if (identityDialog) {
        OptionsSheet(
            title = "选择身份",
            options = RegionData.identityOptions,
            selected = data.identity,
            onPick = { idt ->
                identityDialog = false
                patch({ it.copy(identity = idt) }, mapOf("identity" to idt))
            },
            onClose = { identityDialog = false },
        )
    }
    if (pwdDialog) {
        PwdDialog(
            onDismiss = { pwdDialog = false },
            onSaved = { pwdDialog = false },
        )
    }
    if (logoutDialog) {
        DialogSheet(visible = true, onDismiss = { logoutDialog = false }, title = "退出登录") {
            Tx(text = "确定退出当前账号吗？", color = Miu.textSecondary, size = 14.sp)
            Spacer(Modifier.height(10.dp))
            DialogActions(
                primary = "退出",
                onClickPrimary = {
                    logoutDialog = false
                    ChatModel.resetForLogout()
                    Router.backToChat()
                },
                soft = "取消",
                onClickSoft = { logoutDialog = false },
            )
        }
    }
    }
}

@Composable
private fun IdentityCard(data: ProfileData) {
    val labels = data.roles.mapNotNull(::roleLabel).distinct().ifEmpty { listOf("用户") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = data.nickname?.ifBlank { data.username } ?: data.username, size = 64.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Tx(
                text = data.nickname?.ifBlank { data.username } ?: data.username,
                size = 19.sp,
                bold = true,
                maxLines = 1,
                ellipsis = true,
            )
            Tx(text = "@${data.username}", color = Miu.textSecondary, size = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { l ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Miu.primarySoft)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Tx(text = l, color = Color(0xFF3482FF), size = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSection(title: String, content: @Composable () -> Unit) {
    Tx(
        text = title,
        color = Miu.textSecondary,
        size = 13.sp,
        bold = true,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White),
    ) {
        content()
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun InfoRow(label: String, value: String, editable: Boolean = true, onClick: () -> Unit) {
    RowItem(onClick = { if (editable) onClick() }) {
        Tx(text = label, size = 15.sp, modifier = Modifier.weight(1f))
        Tx(text = value, color = Miu.textSecondary, size = 14.sp, maxLines = 1, ellipsis = true)
        if (editable) {
            Spacer(Modifier.width(6.dp))
            Chevron()
        }
    }
}

/** 单行文本编辑弹窗：保存返回 null 成功，否则返回错误文案。 */
@Composable
private fun TextEditDialog(
    title: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> String?,
) {
    var value by remember { mutableStateOf(initial) }
    var err by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DialogSheet(visible = true, onDismiss = onDismiss, title = title) {
        Field(
            value = value,
            onValueChange = { value = it; err = null },
            hint = hint,
            modifier = Modifier.fillMaxWidth(),
        )
        err?.let {
            Spacer(Modifier.height(6.dp))
            Tx(text = it, color = Color(0xFFE94634), size = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        DialogActions(
            primary = if (saving) "保存中…" else "保存",
            onClickPrimary = {
                if (saving) return@DialogActions
                saving = true
                scope.launch {
                    val e = onSave(value)
                    if (e != null) err = e
                    saving = false
                }
            },
            soft = "取消",
            onClickSoft = onDismiss,
        )
    }
}

@Composable
private fun PwdDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var neu by remember { mutableStateOf("") }
    var neu2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DialogSheet(visible = true, onDismiss = onDismiss, title = "修改密码") {
        Field(value = old, onValueChange = { old = it }, hint = "当前密码", password = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Field(value = neu, onValueChange = { neu = it }, hint = "新密码（至少 6 位）", password = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Field(value = neu2, onValueChange = { neu2 = it }, hint = "再次输入新密码", password = true, modifier = Modifier.fillMaxWidth())
        err?.let {
            Spacer(Modifier.height(6.dp))
            Tx(text = it, color = Color(0xFFE94634), size = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        DialogActions(
            primary = if (saving) "提交中…" else "确认修改",
            onClickPrimary = {
                if (saving) return@DialogActions
                if (neu.length < 6) {
                    err = "新密码至少 6 位"
                    return@DialogActions
                }
                if (neu != neu2) {
                    err = "两次输入的新密码不一致"
                    return@DialogActions
                }
                saving = true
                scope.launch {
                    try {
                        Api.changePassword(old, neu)
                        ToastHost.show("密码修改成功")
                        onSaved()
                    } catch (e: Exception) {
                        err = e.message ?: "修改失败"
                    } finally {
                        saving = false
                    }
                }
            },
            soft = "取消",
            onClickSoft = onDismiss,
        )
    }
}
