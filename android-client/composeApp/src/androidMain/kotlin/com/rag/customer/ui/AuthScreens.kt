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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rag.customer.core.Router
import com.rag.customer.core.Route
import com.rag.customer.core.Session
import com.rag.customer.core.ToastHost
import com.rag.customer.data.Api
import com.rag.customer.state.ChatModel
import kotlinx.coroutines.launch

/** 登录页：手机号/账号 + 密码（镜像 mp login）。 */
@Composable
fun LoginScreen() {
    var account by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var pwdVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        val a = account.trim()
        if (a.isEmpty()) {
            ToastHost.show("请输入手机号 / 账号")
            return
        }
        if (pwd.isEmpty()) {
            ToastHost.show("请输入密码")
            return
        }
        loading = true
        scope.launch {
            try {
                val data = Api.login(a, pwd)
                val me = runCatching { Api.me() }.getOrNull()
                Session.saveLogin(data.token, data.username, me)
                ToastHost.show("登录成功，欢迎回来")
                ChatModel.onLoginReturned() // 游客转人工 → 登录后自动续转
                Router.pop()
            } catch (e: Exception) {
                ToastHost.show(e.message ?: "登录失败")
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Miu.pageBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(44.dp))
        Box(
            modifier = Modifier
                .noRipple { Router.pop() }
                .width(40.dp)
                .height(40.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Tx(text = "‹", size = 30.sp, color = Miu.textMain)
        }
        Spacer(Modifier.height(14.dp))
        Tx(text = "欢迎回来", size = 28.sp, bold = true)
        Spacer(Modifier.height(8.dp))
        Tx(text = "登录后可使用转人工客服、历史记录等完整功能", color = Miu.textSecondary, size = 14.sp)
        Spacer(Modifier.height(32.dp))

        Field(
            value = account,
            onValueChange = { account = it },
            hint = "手机号 / 账号",
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Field(
            value = pwd,
            onValueChange = { pwd = it },
            hint = "密码",
            password = true,
            passwordVisible = pwdVisible,
            onTogglePassword = { pwdVisible = !pwdVisible },
            imeAction = ImeAction.Done,
            onDone = { if (!loading) submit() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(30.dp))

        if (loading) {
            SoftButton(text = "登录中…", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
        } else {
            FilledButton(text = "登  录", onClick = { submit() }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Tx(text = "还没有账号？", color = Miu.textSecondary, size = 14.sp)
            Tx(
                text = "  去注册",
                color = Miu.primary,
                size = 14.sp,
                bold = true,
                modifier = Modifier.noRipple { Router.push(Route.Register) },
            )
        }
        Spacer(Modifier.height(60.dp))
    }
}

/** 注册页：手机号 + 昵称(可选) + 密码 + 确认（镜像 mp register，成功即自动登录）。 */
@Composable
fun RegisterScreen() {
    var phone by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }
    var pwdVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun register() {
        val p = phone.trim()
        if (p.isEmpty()) {
            ToastHost.show("请输入手机号")
            return
        }
        if (p.length < 11) {
            ToastHost.show("请输入正确的手机号")
            return
        }
        if (pwd.length < 6) {
            ToastHost.show("密码至少 6 位")
            return
        }
        if (pwd != pwd2) {
            ToastHost.show("两次输入的密码不一致")
            return
        }
        loading = true
        scope.launch {
            try {
                Api.register(p, pwd, pwd2, nickname.trim().ifEmpty { null })
                val data = Api.login(p, pwd)
                val me = runCatching { Api.me() }.getOrNull()
                Session.saveLogin(data.token, data.username, me)
                ToastHost.show("注册成功，欢迎使用！")
                ChatModel.onLoginReturned()
                Router.backToChat()
            } catch (e: Exception) {
                ToastHost.show(e.message ?: "注册失败")
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Miu.pageBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Box(
            modifier = Modifier
                .noRipple { Router.pop() }
                .width(40.dp)
                .height(40.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Tx(text = "‹", size = 30.sp, color = Miu.textMain)
        }
        Spacer(Modifier.height(8.dp))
        Tx(text = "创建账号", size = 28.sp, bold = true)
        Spacer(Modifier.height(8.dp))
        Tx(text = "注册后可转人工客服、查看历史对话", color = Miu.textSecondary, size = 14.sp)
        Spacer(Modifier.height(30.dp))

        Field(
            value = phone,
            onValueChange = { phone = it.filter(Char::isDigit).take(11) },
            hint = "手机号",
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Field(
            value = nickname,
            onValueChange = { nickname = it },
            hint = "昵称（可选）",
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Field(
            value = pwd,
            onValueChange = { pwd = it },
            hint = "密码（至少 6 位）",
            password = true,
            passwordVisible = pwdVisible,
            onTogglePassword = { pwdVisible = !pwdVisible },
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Field(
            value = pwd2,
            onValueChange = { pwd2 = it },
            hint = "确认密码",
            password = true,
            passwordVisible = pwdVisible,
            onTogglePassword = { pwdVisible = !pwdVisible },
            imeAction = ImeAction.Done,
            onDone = { if (!loading) register() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(30.dp))

        if (loading) {
            SoftButton(text = "注册中…", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
        } else {
            FilledButton(text = "注册并登录", onClick = { register() }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(60.dp))
    }
}
