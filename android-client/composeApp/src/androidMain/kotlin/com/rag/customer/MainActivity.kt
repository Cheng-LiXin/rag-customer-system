package com.rag.customer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.rag.customer.core.AppCtx
import com.rag.customer.ui.SplashGate
import com.rag.customer.ui.SplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 先注入 Application Context：Prefs / Session / OkHttp 首次读取依赖它
        AppCtx.app = applicationContext
        setContent {
            // 主界面（路由分发，默认落首页 Chat）常驻；
            // 冷启动时其上覆盖科技感落地页，点「快速开始」后淡出进入首页。
            Box(modifier = Modifier.fillMaxSize()) {
                App()
                AnimatedVisibility(
                    visible = SplashGate.pending,
                    enter = fadeIn(tween(500)),
                    exit = fadeOut(tween(450)),
                ) {
                    SplashScreen(onEnter = { SplashGate.pending = false })
                }
            }
        }
    }
}
