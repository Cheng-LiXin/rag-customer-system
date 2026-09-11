# 知识库导入切片工具（kb_csv_builder）

把零散源文档（`.md` / `.txt`）**按标题 / 段落自动切块**，输出成系统「知识库导入」的 CSV。

> 格式与后端 `KnowledgeServiceImpl.toCsv / importCsv` 完全一致：
> 表头 `标题,分类ID,内容,关键词,来源链接`（第一行导入时被跳过）；列顺序固定；UTF-8(BOM)，Excel 可直接打开。

## 目录

```
kb-import/
├─ kb_csv_builder.ps1     # 脚本（PowerShell 7，零第三方依赖）
├─ input/                 # 默认输入目录：把 .md/.txt 丢进来（支持子目录）
└─ README.md
```

## 用法

```powershell
# 1) 最简：处理 input\ 下所有 .md/.txt，输出 knowledge_import.csv（脚本同级）
./kb_csv_builder.ps1

# 2) 自定义目录与输出，全部归到分类 0（=不分类）
./kb_csv_builder.ps1 -InputDir .\docs -Output .\kb.csv

# 3) 按子文件夹名映射分类ID（推荐：每类文档放一个子文件夹）
./kb_csv_builder.ps1 -InputDir .\docs -Output .\kb.csv `
    -CategoriesJson .\categories.json

# categories.json 示例（键 = 子文件夹名，值 = knowledge_category.id）：
# { "教务服务": 1, "学工服务": 2 }

# 4) 调每条的字符预算（默认 150~500 字，检索质量较好）
./kb_csv_builder.ps1 -MinChars 200 -MaxChars 600

# 5) 关闭内容去重（默认开启：完全相同的正文只保留第一条）
./kb_csv_builder.ps1 -NoDedup

# 6) 回填来源链接：web-collect 采集的知识请带上采集清单
#    （清单列: 栏目,分类ID,文章标题,字数,来源URL,本地文件；热门知识 Top10 按它把同一源文的碎片归并为一行）
./kb_csv_builder.ps1 -InputDir ..\web-collect\out -Output .\kb.csv -Manifest ..\web-collect\out\manifest_20260902_155215.csv
#    不传 -Manifest 时脚本会自动检测输入目录下的 manifest_*.csv
```

## 切片规则（决定检索质量，重要）

- **Markdown `#` 标题 = 一条新知识的边界**，标题文字直接作为该条的「标题」列；无正文的标题会被跳过。
- 标题下的正文按空行拆成**段落**，再把相邻段落按字符预算 `MinChars~MaxChars` 聚成一条。**每条都是一个语义完整的问答 / 主题**。
- 无标题的纯文本文件：整个文件按段落切，标题列取文件名。
- 自动忽略：图片行、分隔线、HTML 注释、代码围栏。
- 建议单条控制在 **150–500 字**：太大向量会稀释、命中不准（系统检索 Top-K 默认 3，`content` 是 LONGTEXT 但不宜过长）。

## 导入到系统

```bash
# 1. 先建好知识分类，拿分类ID
#    POST /api/knowledge/category  或后台「知识库管理」界面创建
# 2. 拿 admin token 并上传
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | ...)  # 取 data.token
curl -X POST http://localhost:8080/api/knowledge/chunk/import -H "Authorization: Bearer $TOKEN" -F "file=@knowledge_import.csv"
```

返回 `{ success, fail, errors }`。`success` **只代表写入 MySQL**，向量化失败会单独把该条标成 `vectorStatus=2`——导入后务必到后台/`GET /api/knowledge/chunk/page?status=2` 复查，失败条目用 `POST /api/knowledge/chunk/{id}/reindex` 重建。

## 常见问题

- **GBK 编码的 .txt**：脚本先按 UTF-8 读、失败自动退到 GB18030，两种都能处理。
- **来源链接列**：采集知识（web-collect）带 `-Manifest` 时按文件名自动回填，也可不传、由脚本**自动检测输入目录下的 `manifest_*.csv`**；无清单的手写文档该列留空即可（`source_type` 默认 MANUAL，无需填）。
- **关键词列**：脚本留空，需要时可在 Excel 里补。
- **重复导入**：系统端不去重。脚本默认在**生成 CSV 时**按正文精确去重，但如果同一 CSV 上传两次，系统仍会产生重复——重导前先删旧批次。
- **文件命名建议**：同一主题多份文档切出的标题容易撞名，可在每份文档里写一个 `# 文档标题`，避免所有条目标题都是文件名。
