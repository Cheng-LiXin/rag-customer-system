# 离线量化评估（Golden Set 跑分）

> 目标：让「优化前 / 优化后」有基线、有对比、有来源。所有指标必须能一条命令复现。

## 一条命令验证全部改动（推荐入口）

```powershell
pwsh tools\verify_all.ps1              # 全量：单元检查 + 探针 + 跑分 + 保真 + 攻防
pwsh tools\verify_all.ps1 -SkipDemo    # 不动知识库（间接注入那几行会显示未拦截）
pwsh tools\verify_all.ps1 -SkipBackend # 只跑不需要后端的单元检查
pwsh tools\verify_all.ps1 -Limit 8     # 快速冒烟
```

输出一张 PASS/FAIL 汇总表并给出真实测量值，任一项不达标退出码 1。
它覆盖：防护规则单元测试、行内引用渲染回归、**缓存旁路探针**、跑分红线断言、
逐字保真断言、注入攻防 30 样本。

> 这套断言做过**负向验证**（把阈值抬到不可能达标的值），确认它们真的会 FAIL
> —— 一个永远说 PASS 的验证脚本等于没有。

## 0. 前置条件（**必须先做**，否则指标全是废的）

1. **后端以评估模式启动**，打开 qa:cache 旁路开关：

   ```powershell
   cd E:\rag-customer-system
   $env:RAG_EVAL_BYPASS="true"
   mvn spring-boot:run
   ```

   **为什么必须旁路缓存**：`qa:cache` 按 `MD5(问题)` 命中后直接返回旧答案、不再检索。
   评估集里同一个问题会被反复跑（换模式、换阈值），一旦命中缓存，
   Recall/MRR/TTFT 全部变成上一次的残留值 —— 指标会「看起来很好但完全失真」。
   开关默认 `false`，生产环境即使被传 `?noCache=true` 也不会旁路（见 `ChatController.bypass()`）。

2. 知识库已导入且 `vector_status = 1`（跑分只检索，不向量化）：

   ```sql
   SELECT COUNT(*) FROM knowledge_chunk WHERE deleted=0 AND vector_status=1;
   ```

3. Python 用全路径（本机 `python` 是 Windows 商店占位符）：

   ```
   D:\Program\Python\Anaconda3\envs\myenv\python.exe
   ```

## 1. 跑分

```powershell
$PY = "D:\Program\Python\Anaconda3\envs\myenv\python.exe"

# 全量（ask 模式，服务端返回 timings）
& $PY tools\eval\run_eval.py --set tools\eval\golden_set.csv --tag baseline

# 客户端口径的 TTFT（SSE 首个 token 到达）
& $PY tools\eval\run_eval.py --set tools\eval\golden_set.csv --tag baseline-stream --mode stream

# 抽查前 8 条
& $PY tools\eval\run_eval.py --tag smoke --limit 8
```

产物：`tools/eval/results/<时间戳>_<tag>.csv`（逐题明细，UTF-8 BOM 便于 Excel 打开）。

## 2. 指标口径（写论文/报告时以此为准）

| 指标 | 定义 | 目标 |
|---|---|---|
| 兜底率 | `expect=answer` 的题中，答案等于固定兜底文案的比例 | **不得高于基线**（红线） |
| 正常题误拒率 | `expect=answer` 的题被判为「拒答」的比例 | **≈ 0**（红线） |
| 超纲拒答率 | `expect=reject` 的题被拒答的比例 | **> 90%** |
| 超纲未答非所问率 | 拒答**或**回兜底文案的比例（更宽松，避免高估） | — |
| Recall@K / MRR | 仅对填了 `gold_chunk_ids` 的题计算 | Recall@5 > 85% |
| TTFT | `stream` 模式 = 客户端首个 token 事件到达耗时；`ask` 模式 = 服务端 `timings.ttftMs` | < 1.5s |
| 端到端时延 | 整题从发出到收到完整答案 | — |

### ⚠️ TTFT 口径必须如实说明（不要藏）

本项目的 SSE 是**伪流式**：抽取式回答的答案文本在 `Flux` 创建**之前**就已经完整算出
（`ChatServiceImpl.stream()`），之后只是把成品按 18 字符切片推出去。因此

> **TTFT ≈ 检索 + 生成的全链路耗时**，与「端到端时延」几乎相等。

这是该实现形态的固有性质，不是 bug，也**不能**包装成「真流式低延迟」。
报告里按此口径分开呈现：

- **主指标 = 冷路径（cache-miss）TTFT p50/p95**
- **附报 = 热路径（cache-hit）TTFT**（接近 0，是缓存设计的正面证据）
- 若冷路径未达标，给出优化项与量化收益（如提高缓存命中率、降低 `candidate-k`）。

### Recall@K 的测量层

**这是本项目评估里最容易搞错的一点。** 有两条独立的测量路径：

- **答案路径**（`--mode ask/stream`，走 `/api/chat/ask`）：`sources` 只有 `rag.top-k=3` 条，
  所以在这一层算出来的「Recall@5」**实际是 Recall@3**（脚本会显式提示 `实际 K=3`）。
  这条路量的是：兜底率 / 拒答率 / 正常题误拒率 / TTFT / Faithfulness。
- **检索路径**（`--retrieval`，走 ADMIN 接口 `/api/knowledge/chunk/retrieve-preview`）：
  直接拿检索器的 top-K（K 可指定 5），**这才是 Recall@K / MRR 的正确测量层**。

```powershell
# 检索层跑分：四组检索对比就在这一层做
& $PY tools\eval\run_eval.py --retrieval --k 5 --retrieval-mode vector --tag v
& $PY tools\eval\run_eval.py --retrieval --k 5 --retrieval-mode bm25   --tag b
& $PY tools\eval\run_eval.py --retrieval --k 5 --retrieval-mode rrf    --tag r
& $PY tools\eval\run_eval.py --retrieval --k 5 --retrieval-mode rerank --tag rr

# 漏召回归因：区分「标注错了」还是「检索真的漏了」
& $PY tools\eval\analyze_misses.py
```

**为什么不把答案路径的 top-k 直接提到 5**：那会把 2 个噪声片段塞进抽取池、
污染已经调好的答案质量（兜底率、逐字保真都会退）。指标归指标，答案质量归答案质量，两者解耦。

### 多跳题必须看「全召回率」

多跳题（`type=multihop`）需要**两个不同文档**的来源都召回才算答得全。
只看「命中其一」会严重高估，所以脚本对多跳题另算 `full_recall`（两个来源都进 top-K 的比例）。

### 逐字保真断言里的「格式差异」

`verify_verbatim.py` 严格比对不通过时，会再用**去掉标点**的宽松比对复核一次：
若宽松比对能过，说明差异来自**渲染层的展示变换**而非事实改写，记入 `soft`（格式差异）
并在报告里单独列出，**不算失败**。

最典型的来源是「单行表格退化成 `标签：内容`」（`ChatServiceImpl.toInlineTableRow`）：
源片段 `| 百分制成绩 | 等级制成绩 | 绩点 |` 渲染成 `百分制成绩：等级制成绩；绩点`，
标点是渲染层补的，事实内容一字未改。

这样处理的好处是**既不误报、也不放水**：真正被改写或幻觉的句子，去掉标点后依然对不上原文。

## 3. Golden Set 字段

`golden_set.csv`（UTF-8，表头固定）：

| 字段 | 说明 |
|---|---|
| `id` | 题号，如 `N01` / `M01` / `O01` |
| `type` | `normal` 常规 / `multihop` 跨文档多跳 / `out_of_scope` 超纲陷阱 |
| `question` | 问题原文 |
| `gold_chunk_ids` | 正确命中的 `knowledge_chunk.id`，多个用逗号分隔（可留空，batch D 标注） |
| `gold_source_urls` | 官方来源链接（可选） |
| `gold_keywords` | 答案应出现的关键词，分号分隔（人工抽检用） |
| `expect` | `answer` 应给出实质回答 / `reject` 应拒答 |
| `notes` | 出题依据 |

以 `#` 开头的 `question` 行会被跳过（可当注释）。

## 4. 相关脚本

| 脚本 | 用途 | 阶段 |
|---|---|---|
| `eval_common.py` | 公共工具（HTTP/CSV/指标/剥引用标记） | A |
| `probe_env.py` | **环境探针**：后端可达 + 缓存旁路是否真生效（跑分前提） | A |
| `run_eval.py` | 跑分主程序（`--assert` 做红线断言） | A |
| `verify_verbatim.py` | 逐字保真断言（答案每句必须是召回片段的逐字子串） | A |
| `analyze_misses.py` | 漏召回分析：区分「标注错了」还是「检索真的漏了」 | D |
| `judge_faithfulness.py` | DeepSeek 裁判算 Faithfulness（MD5 缓存） | D |
| `threshold_sweep.py` | 拒答阈值扫描（**离线**模拟，不必改配置重启） | D |
| `report.py` | 汇总生成 `docs/评估报告.md` / `docs/检索对比表.md` | D |

> 没有 `annotate.py`：原计划用它辅助标注，实际改为**按知识库正文出题时直接标 gold**
> （更可靠 —— 若先看检索结果再标 gold，等于拿答案去凑题目）。标注质量改由
> `analyze_misses.py` 复核。

> `verify_all.ps1`（在 `tools/` 下）是上面的编排入口，日常验证跑它即可。
