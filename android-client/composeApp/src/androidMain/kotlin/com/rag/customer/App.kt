package com.rag.customer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import com.rag.customer.core.AuthExpiry
import com.rag.customer.core.Router
import com.rag.customer.core.Route
import com.rag.customer.core.ToastLayer
import com.rag.customer.ui.ChatScreen
import com.rag.customer.ui.HistoryScreen
import com.rag.customer.ui.LoginScreen
import com.rag.customer.ui.Miu
import com.rag.customer.ui.ProfileScreen
import com.rag.customer.ui.RegisterScreen
import com.rag.customer.ui.SettingsScreen

/** 应用根：路由分发 + 鉴权失效回调 + 系统返回键 + 全局 Toast。 */
@Composable
fun App() {
    // 注册鉴权失效钩子：HTTP 401/403 时清除登录态并压入登录页
    DisposableEffect(Unit) {
        AuthExpiry.onExpire = { Router.forceLogin() }
        onDispose { AuthExpiry.onExpire = null }
    }

    // 子页支持系统返回键
    BackHandler(enabled = Router.canBack) { Router.pop() }

    Box(modifier = Modifier.fillMaxSize().background(Miu.pageBg)) {
        when (val r = Router.current) {
            Route.Chat -> ChatScreen()
            Route.Login -> LoginScreen()
            Route.Register -> RegisterScreen()
            Route.History -> HistoryScreen()
            Route.Profile -> ProfileScreen()
            Route.Settings -> SettingsScreen()
        }
        ToastLayer()
    }
}
