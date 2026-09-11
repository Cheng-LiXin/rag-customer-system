package com.rag.customer.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 顶层路由（mp 导航对应）：Chat 常驻根，其余为可返回子页。 */
sealed class Route {
    object Chat : Route()
    object Login : Route()
    object Register : Route()
    object History : Route()
    object Profile : Route()
    object Settings : Route()
}

/**
 * 极简页面栈导航（等价 mp navigateTo/reLaunch）。无第三方 nav 依赖，
 * 由 App 根读取 [stack] 决定渲染哪一层。
 */
object Router {
    private var _stack by mutableStateOf(listOf<Route>(Route.Chat))

    val stack: List<Route> get() = _stack
    val current: Route get() = _stack.last()
    val canBack: Boolean get() = _stack.size > 1

    fun push(r: Route) {
        if (current != r) _stack = _stack + r
    }

    fun pop() {
        if (_stack.size > 1) _stack = _stack.dropLast(1)
    }

    fun backToChat() { _stack = listOf(Route.Chat) }

    /** 鉴权失效：保留根 Chat，把 Login 压栈（登录成功回退即回原页）。 */
    fun forceLogin() {
        if (current != Route.Login) {
            _stack = _stack.filterNot { it == Route.Login || it == Route.Register } + Route.Login
        }
    }
}
