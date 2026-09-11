# 智能客服助手 · Android 端（毕设展示 APK）

原生 Android 端（Kotlin + Jetpack Compose Multiplatform，仅 Android target）。
界面范式对齐 **Miuix**（主蓝 `#3482FF`、大圆角、卡片化），全部控件基于
androidx 基础原语手写，不依赖 material 组件。仅**用户端**：智能问答、转人工(WebSocket)、
满意度评价、历史对话、个人资料 —— 不含客服工作台与管理后台。**后端零改动**。

## 目录

```
composeApp/src/androidMain/kotlin/com/rag/customer/
├── MainActivity.kt         入口：注入 AppCtx → setContent { App() }
├── App.kt                  根路由分发 + 系统返回键 + 全局 Toast
├── core/                   Session / Router / Server / Infra(AppCtx·Prefs·Toast·MainThread)
├── data/                   Models(后端 DTO) / Api(OkHttp) / Region(省·市·身份)
├── net/WsHub.kt            人工客服 WebSocket（原生 RFC6455 + 指数退避重连）
├── state/ChatModel.kt      问答/转人工/排队/满意度 状态机（单例）
└── ui/                     Kit(Miuix 范式组件库) / MdText(markdown 渲染)
                            ChatScreen / AuthScreens / HistoryScreen / ProfileScreen / SettingsScreen
```

## 技术栈 / 版本

Kotlin 2.4.0 · Compose Multiplatform 1.11.1 · AGP 8.13.2 · Gradle 9.5.0（wrapper）·
JDK 17（编译目标）· minSdk 26 · targetSdk/compileSdk 37 · OkHttp 4.12 · kotlinx-serialization 1.11.
（版本矩阵锁定于 `gradle/libs.versions.toml`，勿随意升降。）

> 构建已跑通：`gradlew :composeApp:assembleDebug` → **BUILD SUCCESSFUL**（2026-09-08，
> Gradle 9.5.0）。产物 `composeApp/build/outputs/apk/debug/composeApp-debug.apk`（≈10.8 MB）。
> 仅有 AGP/Compose 弃用警告，不影响产物。

## 构建 Debug APK

后端需已启动（MySQL/PostgreSQL + Redis + `DEEPSEEK_API_KEY`），Android 端无需连后端也能编译。

**JDK / Gradle 版本**：Gradle 版本由 wrapper 决定（`gradle/wrapper/gradle-wrapper.properties`
= 9.5.0，本机发行版**已缓存**，CLI 与 Android Studio 共用，不再重复下载 ~140MB）。
Gradle 守护进程 JVM 用启动器 JVM（AS 自带 JBR21 / 命令行 `JAVA_HOME`）即可，本项目**未配置**
`gradle-daemon-jvm.properties`（曾手写 criteria 钉 JDK 17 + foojay 插件导致 Android Studio 二次
打开时 Gradle 静默失败、无 Build 按钮，已回滚；教训：**不要手写 daemon criteria**）。
如确要钉 JDK 17：跑 `gradlew updateDaemonJvm --jvm-version=17` 由官方生成器按本机写平台 URL，
或在 AS 的 Gradle JDK 下拉里直接选已装 JDK。

方式 A —— Android Studio（推荐）：
1. `File → Open` 选择本目录（`android-client/`），等待 Gradle Sync。
2. 菜单 `Build → Build App Bundle(s) / APK(s) → Build APK(s)`。
3. 产物：`composeApp/build/outputs/apk/debug/composeApp-debug.apk`。

方式 B —— 命令行：
```
cd android-client
gradlew :composeApp:assembleDebug
```
本机 Gradle 9.5.0 发行版已缓存，直接编译；换机器首次会联网下载 ~140MB 发行版 + 依赖。APK 路径同上。

## 真机安装与联调

**推荐：USB + adb 反向端口映射**（不要求手机与电脑同一 Wi-Fi，手机走移动数据也行）

1. USB 连接手机，允许调试（`adb devices` 能看到设备）。
2. 跑一次 `pwsh -File .\android-client\adb-reverse.ps1`（等价于 `adb reverse tcp:8080 tcp:8080` + 连通性自测）。
3. App 默认地址即 `http://127.0.0.1:8080`（见 `core/Server.kt` 的 `DEFAULT`），装上即可用，无需在应用内改。
4. **拔插 USB / 重启 adb / 重启电脑后需重跑一次映射**；后端保持 8080 监听即可（`java -cp ... com.rag.Application`）。

其它两种情形：

- **同一 Wi-Fi 走局域网 IP**：手机连上与电脑相同的 Wi-Fi，在应用内「服务器设置」（个人中心 → 更多）填电脑局域网 IP，如 `http://10.80.32.136:8080`；后端需以 `0.0.0.0:8080`（非 `127.0.0.1`）监听。
- **模拟器**：把 `Server.DEFAULT` 换回 `http://10.0.2.2:8080`，无需映射。

> 清空应用数据（或重装）后地址会回到 `DEFAULT`，所以写死成 `127.0.0.1:8080` 可保证 USB 联调下"清了数据也不丢配置"。

## 功能与登录门槛（镜像小程序端）

| 功能 | 游客 | 登录用户 |
|---|---|---|
| 智能问答（SSE/抽取式 markdown） | ✅ | ✅ |
| 参考来源 | ✅ | ✅ |
| 转人工客服（WS 排队/接通/结束） | 提示登录 | ✅ |
| 满意度评价 | — | ✅ |
| 历史对话回放 | 提示登录 | ✅ |
| 个人资料（省市·身份·邮箱·昵称30天限改·改密码） | 提示登录 | ✅ |

账号：可用 Web 端注册（手机号自助注册），或 demo 账号 user/user123。

## 交互要点

- 底部输入「发送」或 IME「发送(Send)」出题；回答打字机播放，完成后显示
  意图/缓存标签、参考来源（点来源看片段正文）。
- 建议提问 6 连问（与知识库命中对齐）。
- 人工客服气泡「客」头像浅蓝；顶部状态行区分排队/接通；接通后可双向实时聊天，
  客服结束或超时会话弹满意度星级。
- 右上角 🕘 历史记录 → 点会话回首页自动回放该会话。
- 换服务器地址后自动清空旧会话游标并断开旧 WS。

## 已知边界

- 字体/emoji 走系统字体；emoji 表情可能随厂商略有差异。
- 正文 markdown 支持 **小标题 / -·•列表 / 数字列表 / 竖线表格 / 行内加粗 / 链接去壳**；
  超过屏幕宽度的表格单元格会换行显示（非横滑）。
