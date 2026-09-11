# Handoff：RAG 智能客服系统 · 文档/图表轮次交接

> 生成时间：**2026-09-10**。本轮**未改动任何应用代码**（`src/`、`frontend/`、`mp-client/`、`android-client/` 全部零改动），只新增了 `docs/` 下的三张图 + 一份说明文档。
>
> 上一版 HANDOFF（2026-09-09，文档上传导入轮）的内容已并入 `CLAUDE.md`；其中**两条 CLAUDE.md 未收录的运行时坑已在本文件「环境与命令」段保留**（8080 排除端口、Android adb reverse）。

---

## 背景 / 目标

毕设答辩材料补充：把系统「讲清楚」。产出一套**可交互、可切明暗主题、可缩放**的图 + 一份带代码行号的文字说明，覆盖
① 系统整体架构 ② 用户提问到系统回答的全链路 ③ 人工客服会话状态机。验收标准 = 三张图都能过 archify 的 `validate`（9/9 检查）
与 `visual-check`（1440×900 等四种桌面尺寸容器不溢出）。

## 当前状态

### 已完成（本轮，全部有验证证据）

| 产物 | 类型 | 校验 | 浏览器实测 |
| --- | --- | --- | --- |
| `docs/rag-customer-system-architecture.html` | architecture，9 组件 | 9/9，0 error / 0 warning | 四种尺寸 × 明暗，`scrollHeight == innerHeight` |
| `docs/rag-qa-sequence.html` | sequence，9 参与者 / 12 消息 | 9/9，0 error / 0 warning | 同上，投影字号 6.97~7px |
| `docs/rag-conversation-lifecycle.html` | lifecycle，5 状态 / 4 迁移 | 9/9，0 error / 0 warning | 同上，投影字号 6.79~7px |
| `docs/用户提问全链路说明.md` | 文字说明 | — | 逐段标注 `文件:行号` 代码索引 |

- 每张图都有 `同名 .json` 源文件（改图改 JSON，用 `deliver` 重新出 HTML）。
- 三张图我都**逐张看过实际渲染截图**（2048×1320 明色），确认文字无重叠、标签不压节点、无残留溢出碎字。
- 架构图的结论：**必须用「宽而扁」的两行布局**。原来三行（比例 0.54）在任何卡片数量下都塞不进 1440×900，压成两行才过。
- 时序图/状态机的关键取舍：状态机的 `已结束` **不能**放进 terminal band（y=450），否则 viewBox 高需 ≥630、比例 0.58 直接顶穿容器门限 → 5 个状态全放主轨，下两条泳道当带标签的通道区用。
- **本轮代码零改动**，因此**无需重启 8080 即可看到全部产物**（直接开 HTML 文件即可）。

### 未完成 / 阻塞

**无阻塞项。** 三个待用户拍板的开放项：

1. **visual-check 副产物是否清理**（未答复）。`docs/` 下有三组 `<图名>.visual-check.*`：各 4 张 PNG + 1 个对照页 HTML + 1 个收据 JSON。删掉不影响 HTML 成品。
2. **状态机下两条泳道无状态节点**（未答复）。当前 `02 回收与重开` / `03 结束` 只承载路由通道。若要填实心状态块，可把 `已结束` 拆成 `已结束(manual)` / `已结束(timeout)` 放进 terminal band —— 代价是 viewBox 高度回到 630、需重调比例。
3. 答辩是否需要别的图表（如知识库导入流水线 `dataflow`、部署图 `architecture + engineering_profile`）—— 未提。

## 关键决策与约束

- **画图工具用 archify skill**（`C:\Users\19832\.claude\skills\archify`）。它的硬门限我已实测出来并写进记忆 `archify-diagram-constraints`，**下次画图前务必先读那条记忆**，否则会重蹈本轮 5+ 轮返工。核心两条：
  - **可读性门限**：校验按 ~930px 舞台算缩放，`scale = 930 / viewBoxWidth`，投影字号 < 6px 直接 fail → **viewBox 宽必须 ≲1085**。
  - **容器门限**：viewer 按宽度缩放，要求 `scrollHeight <= innerHeight` → **高宽比 H/W 必须 ≲0.51**。
  - 两刀夹死画布。**架构图例外**：不写 `meta.viewBox` 时渲染器自动算「内容 + 上下各 198 单位固定内边距」；时序/状态机写了 `meta.viewBox` 就**原样采用**。
- **状态机 band 位置是固定的**（main y=126 / event y=278 / terminal y=450；主轨列心 x = 94/248/402/556/710）。**渲染器永远画 3 条 band 标题**，只声明 `main` 时第 2、3 条会漏出**英文默认文案**并被状态框压住 —— 必须显式声明 `main` + `terminal` + 至少一个 event lane 且全写中文标签。
- **状态机 `yOffset` 会把状态移出本 band**、掉进下一条 band 的行里压住标题 —— **别用它做垂直居中**。
- **状态机设计契约**：官方文档明确「是阶段图、不是密集状态迁移图」「transition 标签尽量别进 SVG，信息优先放 node 的 sublabel/tag/卡片」。硬塞 5 条带标签的通道边会被判 `ambiguous-corridor` + 一堆 `label-route-clearance`。**相邻同轨状态之间不要画边**，靠 `step` 01~05 表达顺序。
- **时序图时间轴由 viewBox 反推**：消息 y ∈ [160, H−83]、相邻间距 ≥28px、segment y ∈ [72, H−45] → **消息条数被 H 卡死（约 12 条 / H=551）**。参与者框固定 86px 宽，`column_fit:"spread"` 只拉间距不加宽框；带品牌角标时标签只剩 38px → **标签用 ≤4 个 CJK 字的短词**，长标识放 `sublabel`。
- `deepseek-chat` 对长文逐字复述不稳定这条**依然成立**，本轮写的说明文档里把它当作核心设计动因来讲，别在新文档里改口成「模型自由生成」。

## 环境与命令

### 图表
```powershell
cd E:\rag-customer-system\docs
# 校验（改完 JSON 先跑这个）
node "C:\Users\19832\.claude\skills\archify\bin\archify.mjs" validate sequence rag-qa-sequence.json --quality showcase --json
# 交付（validate 过了才跑；非零退出不能当成功）
node "C:\Users\19832\.claude\skills\archify\bin\archify.mjs" deliver  sequence rag-qa-sequence.json rag-qa-sequence.html --quality showcase --json
# 浏览器实测
node "C:\Users\19832\.claude\skills\archify\bin\archify.mjs" visual-check rag-qa-sequence.html --json
```
`<type>` 取 `architecture` / `sequence` / `lifecycle`，三张图各自对应。

### 应用（本轮未跑，供下轮用）
- 后端：`cd E:\rag-customer-system && mvn spring-boot:run`（8080）；编译校验 `mvn clean compile -DskipTests`
- 前端：`cd frontend && npm run dev`（5173，代理 `/api`→8080）；构建 `npm run build`
- Android：`android-client/` 下 `.\gradlew.bat :composeApp:assembleDebug`（Gradle 9.5.0）
- **8080 当前状态：本轮实测未监听**（`Get-NetTCPConnection -LocalPort 8080` 无结果，2026-09-10）。Docker 容器状态本轮**未验证成功**（命令报错），要用先自行 `docker ps` 确认 `rag-postgres` / `rag-redis`。
- 密钥走**机器环境变量** `DEEPSEEK_API_KEY` / `SILICONFLOW_API_KEY`，子进程自动继承。

### ⚠ 两条 CLAUDE.md 未收录的运行时坑（从上版 HANDOFF 保留）

1. **8080 绑不上 = 可能是 Windows 排除端口，不是进程占用**。报「Port 8080 already in use」但 `netstat` 干净、自绑返回 `WSAEACCES` 时查：
   `netsh interface ipv4 show excludedportrange protocol=tcp`；
   修法 = 管理员 `net stop winnat` + `net start winnat`（已解决过一次，**该窗口会滑动复现**）。**先查这个再怀疑占用。**
2. **Android 真机联调 = `127.0.0.1:8080` + adb reverse**：`adb reverse tcp:8080 tcp:8080`（脚本 `android-client/adb-reverse.ps1`）。
   手机与电脑不同网段/无 WiFi 时局域网 IP 不通；**拔插 USB 后需重跑**。

### 相关文件入口
- 图源与成品：`docs/rag-customer-system-architecture.{json,html}`、`docs/rag-qa-sequence.{json,html}`、`docs/rag-conversation-lifecycle.{json,html}`
- 文字说明：`docs/用户提问全链路说明.md`
- 权威工程约束：`CLAUDE.md`（决策/踩坑/构建校验全在里面，**唯一权威**）；功能总览：`README.md`
- 本轮相关的记忆：`archify-diagram-constraints`（画图约束）、`project-handoff`（代码库状态快照）

## 下一步

1. [ ] 用户拍板上面三个开放项（清理副产物 / 状态机泳道是否填充 / 是否补别的图）。
2. [ ] 若要继续画图：**先读记忆 `archify-diagram-constraints`**，再照「快速上手」的顺序 `validate → deliver → visual-check` 走，别裸写坐标。
3. [ ] 若转回功能开发：先读 `CLAUDE.md`，再确认 8080/Docker/MySQL 是否在跑（本轮 8080 是停的）。
4. [ ] 答辩前建议把三张图的相对路径补一句到 `README.md` 的文档索引里（本轮未做，等用户确认是否需要）。

## 交接时要说清

**本轮只产出文档，没碰代码——所以「图看得见效果」和「系统行为有没有变」是两件事，别把图上的描述当成新功能。**
图里的每一句事实都对过代码（`docs/用户提问全链路说明.md` 每段带 `文件:行号`），但**本轮没有真机/浏览器跑过业务链路**；
若下轮要验证业务行为，仍需按 `CLAUDE.md` 启动依赖并自测。另外画图务必先读 `archify-diagram-constraints` 那条记忆，本轮在它的两条门限上返工了五轮以上。
