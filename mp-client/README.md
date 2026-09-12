# mp-client — 智能客服微信小程序端（uni-app Vue3）

基于 uni-app（Vue3 + TS + Vite，compiler 5.24）实现的微信小程序端，复用后端全部 REST/JWT/WebSocket 能力，**后端零改动**。

对应 Web 端：用户问答 + 人工客服（用户侧）、客服工作台（客服侧）。管理后台（admin）仍留在 Web，不做小程序。

> 代码已 `npm run build:mp-weixin` 编译通过（DONE Build complete）。**尚未在微信开发者工具里真机/模拟器跑通**——需按下方「导入运行」步骤做首次运行验证。

## 目录结构

```
mp-client/
├─ src/
│  ├─ pages.json          # 8 个页面 + tabBar 无（全页面跳转）
│  ├─ manifest.json       # appid 留空，测试号由开发者工具注入
│  ├─ App.vue             # 全局样式（品牌渐变/卡片/胶囊按钮）
│  ├─ utils/
│  │  ├─ config.ts        # ★ BASE_URL / API_BASE / WS_URL / 各 storage key
│  │  ├─ request.ts       # uni.request 封装：Result 解包 + 401/403 清登录跳转
│  │  ├─ ws.ts            # uni.connectSocket 单例（缓冲发送、断线重开）
│  │  └─ bus.ts           # 跨页事件总线（agent 会话页收 workbench 常驻 WS 推送）
│  ├─ stores/auth.ts      # token/username/profile 响应式 + isLoggedIn/isAgent/... 
│  ├─ api/                # auth / chat / agent / conversation / ticket（对齐 Web）
│  ├─ components/MdText.vue # markdown-lite 渲染（**小标题**/- 列表/表格），AI 气泡用
│  └─ pages/
│     ├─ index/index.vue    # 用户问答：首屏建议卡片/打字机动画/转人工/星级/结束会话
│     ├─ login/login.vue
│     ├─ register/register.vue
│     ├─ history/history.vue # 历史对话会话列表
│     ├─ profile/profile.vue # 个人中心（身份卡 + 昵称限改提示；员工隐藏身份/地区）
│     ├─ agent/workbench.vue # 客服工作台（进行中/排队/已结束 + 未读角标 + 常驻 WS）
│     ├─ agent/chat.vue      # 客服会话详情（客户名片/收发/结束/再次接待/建工单）
│     └─ agent/tickets.vue   # 客服「我的工单」（状态筛选 + 1→2→3→4 流转）
├─ dist/build/mp-weixin/   # 编译产物 —— 微信开发者工具导入这个目录
└─ README.md
```

## 后端对接口径（与 Web 一致，已对齐）

- **登录/鉴权**：`POST /api/auth/login` 得 `token`，之后每个请求带 `Authorization: Bearer <token>`；`GET /api/auth/me` 取数字 id 与角色。
- **问答**：**不用 SSE**（小程序无 EventSource）→ `POST /api/chat/ask` `{message, conversationId}` 整段返回，前端打字机动画模拟流式。
- **人工客服**：`POST /api/agent/transfer` 转人工；WebSocket `wx.connectSocket` 连 `/ws/customer-service`，先发注册帧 `{type:'register', role:'agent'|'user', agentId|conversationId}`，再收/发消息帧。
  - 用户侧事件：`message`(AGENT 消息)、`assigned`、`position`(排队)、`closed(reason manual|timeout)`。
  - 客服侧事件：`assigned`、`closed`、`message`(用户来消息)。客服**自己的出站消息不回显**（本端已本地追加）。
  - 客服 WS 由「工作台页」持有常驻（`navigateTo` 下层不销毁），会话页通过 `bus` 收推送、用全局 `wsSend` 发送。
- 工单：客服在会话详情点「＋工单」→ `POST /api/ticket`（自动分配给自己、status=2）；我的工单 `GET /api/ticket/page?assigneeId=<自己的id>&status=`；流转 `PUT /api/ticket/{id}/status?status=`。

## 运行步骤

### 1. 编译

```powershell
cd E:\rag-customer-system\mp-client
npm install          # 首次
npm run build:mp-weixin
# 产物：dist/build/mp-weixin
```

### 2. 启动后端（用户自己启动，8080）

后端已含全部所需接口；需 MySQL/PostgreSQL/Redis 在跑。启动后确认 `GET http://127.0.0.1:8080/api/auth/me` 类接口可用。

### 3. 打开微信开发者工具

1. 新建项目 → 导入 `E:\rag-customer-system\mp-client\dist\build\mp-weixin`。
2. AppID 用**测试号**（「测试号」按钮自动生成）即可，无需注册/审核。
3. 右上角「详情」→「本地设置」→ 勾选 **「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」**（否则本地 http://127.0.0.1 与 ws:// 被拦）。

### 4. 连真机 / 连局域网后端

默认 `src/utils/config.ts` 指向 `http://127.0.0.1:8080`（开发者工具模拟器直连本机可用）。

- **真机预览**时后端需改为**局域网 IP**：在 `config.ts` 把 `BASE_URL` 改成 `http://<本机局域网IP>:8080` 后重新 `npm run build:mp-weixin`，并保证
  ① 后端 `application.yml` 无 IP 白名单限制；② 手机与电脑同一局域网；③ Windows 防火墙放行 8080。真机**勾选「不校验合法域名」后仍建议在真机调试勾选对应选项**（开发者工具→真机调试）。微信小程序真机 http 直连局域网在部分系统需同样放行。

## 演示脚本（毕设双角色）

前提：后端已启动；微信开发者工具里导入了小程序；demo 账号 `agent / Agent@Ysu2026`（客服）、可注册新用户或 `user / User@Ysu2026`（用户）。

1. **用户侧问答**：以 `user` 登录 → 首页点建议卡片或输入「2026 本科新生入学须知」等 → AI 打字机出结构化 markdown 答案（小标题 / 列表 / 表格由 MdText 渲染）。参考来源面板小程序端**不展示**（同 Web 现状）。
2. **转人工**：用户点「转人工」→ 显示排队中（后端最小负载分配，需至少一名客服在线）。
3. **客服侧接单**：登录 `agent` 的工作台（首页右上角菜单「客服工作台」）→ 等系统把用户会话 `assigned` 推送进来（「进行中」出现新会话、带未读角标）→ 点进会话详情回消息。
4. **用户侧收到**：客服回复实时推送到用户气泡；用户可 1~5 星评价、点「结束会话」。
5. **客服收尾**：客服在会话详情「结束」或 10 分钟双方无对话自动结束（`closed` reason=timeout，气泡显示「长时间未对话自动结束」）。客服端可建工单并到「我的工单」流转状态。
6. **再看一遍**：用户首页右上角头像菜单 →「历史对话」可翻看旧会话；个人中心改昵称（30 天限改倒计时）。

> 多客服并发：admin 后台（Web）建多个客服(12)/注册多个用户(11) → 多客服各登录工作台，多用户同时转人工观察最少负载分配与排队 `position`。

## 关键约束备忘

- **千万别用 SSE**：小程序端统一 `POST /api/chat/ask` + 前端打字机。
- **改 `BASE_URL` 后必须重新 `npm run build:mp-weixin`**（编译期内联，不是运行时读文件）。
- **客服端 WS 是工作台页持有**；从会话页直接退出到别处需先回工作台断开，避免重复连接注册帧。
- 小程序 storage key 均带 `mp_` 前缀，与 Web localStorage 隔离；登出时清除。
- 若跑 H5 调试：`npm run dev:h5`（同一套代码，浏览器里可直接调，连 8080 免跨域——后端 CORS 已放开）。这是**不依赖微信开发者工具**的最快联调方式。

## 与 Web 端范围差异（有意为之）

| 能力 | Web | 小程序 |
|---|---|---|
| 用户问答 + 打字机 | ✔ | ✔（整段返回+打字机） |
| 用户转人工/排队/评价/结束 | ✔ | ✔ |
| 历史会话 / 个人中心 | ✔ | ✔（员工隐藏身份·地区） |
| 客服工作台 / 客户名片 / 建工单 | ✔ | ✔ |
| 管理后台（用户/客服管理、知识库、统计、违禁词…） | ✔ | ✘（仅 Web） |
| 消息参考来源正文/「展开查看」 | 仅标题 | ✘（不展示，同 Web 现状） |
