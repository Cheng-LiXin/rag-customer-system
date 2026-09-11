# tools/web-collect —— 燕山大学官网知识采集器（Python，纯标准库）

把燕山大学官方站点（`*.ysu.edu.cn`）的**公开文章页**抓下来，整理成 Markdown，
供 `tools/kb-import/kb_csv_builder.ps1` 切块后导入知识库。**不是全站爬虫**，
只精抓 `config.json` 里配置的栏目（列表页 + 指定文章），请求间隔 ~1s、带重试，
微信 `mp.weixin.qq.com` 与外链一律跳过。

## 环境

- Windows 本机 Python 3.11（Anaconda）：`D:\Program\Python\Anaconda3\envs\myenv\python.exe`
  注意：PATH 上的 `python` 是微软商店占位符，请用全路径或先激活该 conda 环境。
- 无需 `pip install` 任何包。

## 用法

```powershell
& "D:\Program\Python\Anaconda3\envs\myenv\python.exe" collect_ysu.py
& "...\python.exe" collect_ysu.py --fresh        # 忽略已抓记录，全部重抓
& "...\python.exe" collect_ysu.py --output D:\tmp\kb_out
```

## 配置：config.json

每个 `section` 一个栏目（栏目名 = 输出子文件夹名，也是 CSV 分类映射的 key）：

```jsonc
{
  "id": "招生问答",        // 输出到 out/招生问答/
  "category": 1,           // knowledge_category.id（招生政策=1, 教务服务=2, 学工服务=3）
  "seed_articles": ["http://.../info/1512/7045.htm"],  // 直接抓的单篇文章
  "list_url": "http://.../xsxz1.htm"                  // 或给栏目列表页，自动解析 info/N/N.htm
}
```

可调：`timeout`、`retries`、`delay`（区间秒）、`max_articles_per_list`、`min_body_chars`。

## 输出

- `out/<栏目>/NN_<标题>.md`：每篇一个文件，首行 `# 标题`，正文段落空行分隔、
  小标题转 `##`/`###`、列表转 `- `、表格转 `|` 分隔行；多页长文自动拼接。
- `out/manifest_YYYYMMDD_HHMMSS.csv`：来源清单（栏目/分类ID/标题/字数/URL/本地文件），
  便于答辩引用与回查原文。
- `out/.state.json`：已抓 URL → 文件名，重复运行自动跳过（`--fresh` 忽略）。

## 与导入流水线的衔接

```powershell
cd E:\rag-customer-system\tools\kb-import
.\kb_csv_builder.ps1 -InputDir ..\web-collect\out `
    -Output ..\web-collect\knowledge_import.csv `
    -CategoriesJson ..\web-collect\categories.json
```

`categories.json` 把 `out/` 下的栏目文件夹名映射到知识库分类 ID。
生成的 CSV（列：标题,分类ID,内容,关键词,来源链接）用管理员 token 调
`POST /api/knowledge/chunk/import` 导入即可，导入成功 ≠ 向量化完成
（需 `vectorStatus=1`，SiliconFlow 未配置时为 2，可在管理端重索引）。

## 试点范围（2026-09-02）

| 栏目 | 分类ID | 内容 |
|---|---|---|
| 招生问答 | 1 | 招生网·燕山大学2026考生问答（整篇大 FAQ，切多块） |
| 迎新入学须知 | 3 | 迎新网·入学须知列表（校园网/邮箱/一卡通/今日校园…） |
| 新生入学须知2026 | 3 | 主站·2026 本科新生入学须知 |
