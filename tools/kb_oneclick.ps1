#requires -Version 7
<#
.SYNOPSIS
  知识库一键导入：采集(可选) → Markdown切块CSV → 可选小标题美化 → 调后台导入 → 自动修复向量化失败的片段。

.DESCRIPTION
  把「官网采集 / 手工整理好的 Markdown」一键推进本系统的向量知识库，串起现有工具：
    tools/web-collect/collect_ysu.py       （可选，按 config.json 精抓官网文章 -> out/<栏目>/）
    tools/kb-import/kb_csv_builder.ps1     （.md/.txt -> 导入 CSV，150~500字/条、自动回填来源链接）
    tools/kb-import/add_subheadings.py     （可选，对每条生成 6~14 字小标题「小标题——源标题」）
    POST /api/knowledge/chunk/import       （调正在运行的后端导入）
    重索引 loop                              （把 vector_status=2 的失败片段自动 reindex 修复）

  设计要点：
    - 系统导入只增不删，脚本默认只处理“本次新增”的一个子文件夹（-Section），避免整库重导造成重复。
    - 导入只写 MySQL；向量化失败会在库里标记 vector_status=2。脚本导入后可选 -RepairStatus2 自动修复。

.EXAMPLE
  # 0) 仅预览：把某目录 Markdown 切成 CSV，不调后端、不联网（小标题除外）。先看条数与内容再决定是否入库
  ./kb_oneclick.ps1 -DryRun -InputDir .\kb-import\examples\input -CategoriesJson .\kb-import\examples\categories.json

  # 1) 手工整理的知识（每个分类放一个子文件夹，用 categories.json 映射分类ID）：直接入库
  ./kb_oneclick.ps1 -InputDir .\my_new_docs -CategoriesJson .\web-collect\categories.json -RepairStatus2

  # 2) 采集 + 入库某新栏目（先在 web-collect/config.json 配好该栏目；-Section = 采集输出的栏目文件夹名）
  ./kb_oneclick.ps1 -Collect -Section 迎新入学须知 -AddSubheadings -RepairStatus2

  # 3) 采集后只做入库（不重复采集）
  ./kb_oneclick.ps1 -Section 迎新入学须知

.PARAMETER BackendUrl
  正在运行的后端根地址（默认 http://localhost:8080）。注意：代码改动后 8080 若还是旧进程需先重启。
.PARAMETER AdminUser / AdminPass
  管理员账号（DataInitializer 每次启动把密码重置为默认 admin123，通常无需改）。
.PARAMETER PythonPath
  本机真 Python 全路径（系统 PATH 的 python 是商店占位符）。默认 anaconda env。
.PARAMETER Collect
  入库前先跑 web-collect 采集（按 config.json 的 sections；已有记录会自动跳过，相当于增量）。
.PARAMETER Section
  只导入该子文件夹（栏目）下的 Markdown。采集产物在 out/<栏目>，传栏目名即可；手工文档放某个子文件夹也传同名。
  不传时把整个 -InputDir 里的 Markdown 都导入。
.PARAMETER InputDir
  源 Markdown 根目录。默认：-Collect 时 = tools/web-collect/out；否则必填（可指向 out 或任意手工目录）。
.PARAMETER CategoriesJson
  子文件夹名 -> 分类ID 的 JSON。默认自动用 tools/web-collect/categories.json；没有则分类ID=0（不分类）。
.PARAMETER AddSubheadings
  对每条调用 DeepSeek 生成 6~14 字小标题，改写为「小标题——源标题」并输出第6列源标题（需 DEEPSEEK_API_KEY）。
  需要能拿到 manifest（来源清单）才能给手写片段区分；无 manifest 时自动跳过并告警。
.PARAMETER RepairStatus2
  导入后自动修复库里所有 vector_status=2（向量化失败）的片段：逐条 reindex，最多两轮。
.PARAMETER DryRun
  只做采集+切块，打印 CSV 行数与临时路径，不调后端登录/导入。用来在入库前核对切块质量。
.PARAMETER KeepCsv
  保留生成的 CSV 副本（默认放系统临时目录，用完即弃）。配合 DryRun 打印路径以便查看。
.PARAMETER MinChars / MaxChars
  单条内容字符预算（透传给切块器，默认 150~500 字）。
#>
[CmdletBinding()]
param(
    [string]$BackendUrl = 'http://localhost:8080',
    [string]$AdminUser = 'admin',
    [string]$AdminPass = 'admin123',
    [string]$PythonPath = 'D:\Program\Python\Anaconda3\envs\myenv\python.exe',
    [switch]$Collect,
    [string]$Section = '',
    [string]$InputDir = '',
    [string]$CategoriesJson = '',
    [switch]$AddSubheadings,
    [switch]$RepairStatus2,
    [switch]$DryRun,
    [switch]$KeepCsv,
    [int]$MinChars = 150,
    [int]$MaxChars = 500
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot                       # tools 的上一级 = 项目根
$collectDir  = Join-Path $root 'tools\web-collect'
$builder     = Join-Path $root 'tools\kb-import\kb_csv_builder.ps1'
$subheadings = Join-Path $root 'tools\kb-import\add_subheadings.py'

function Log([string]$m) { Write-Host "[$(Get-Date -Format HH:mm:ss)] $m" }
function Fail([string]$m) { Write-Host "[错误] $m" -ForegroundColor Red; exit 1 }

# ---------- Python 解析 ----------
function Resolve-Python {
    if (Test-Path -LiteralPath $PythonPath) { return $PythonPath }
    try { & python --version 2>$null | Out-Null; if ($LASTEXITCODE -eq 0) { return 'python' } } catch { }
    Fail "找不到 Python（$PythonPath）。系统 PATH 的 python 是商店占位符，请 -PythonPath 指向 conda 真 Python（如 D:\Program\Python\Anaconda3\envs\myenv\python.exe）。"
}

# ---------- 1) 采集（可选） ----------
if ($Collect) {
    if (-not $InputDir) { $InputDir = Join-Path $collectDir 'out' }
    $py = Resolve-Python
    Log '步骤1/5：运行 web-collect 采集（已有记录自动跳过）……'
    Push-Location $collectDir
    try { & $py collect_ysu.py; if ($LASTEXITCODE -ne 0) { throw "collect_ysu.py 退出码 $LASTEXITCODE" } }
    finally { Pop-Location }
    Log '采集完成。'
}

# ---------- 确定源目录与分类映射 ----------
if (-not $InputDir) { $InputDir = Join-Path $collectDir 'out' }
if (-not $CategoriesJson) {
    $cand = Join-Path $collectDir 'categories.json'
    if (Test-Path -LiteralPath $cand) { $CategoriesJson = $cand }
}
$srcDir = if ($Section) { Join-Path $InputDir $Section } else { $InputDir }
if (-not (Test-Path -LiteralPath $srcDir)) { Fail "源目录不存在：$srcDir（-InputDir 与 -Section 是否正确？）" }
if (-not (Get-ChildItem -LiteralPath $srcDir -File -Recurse -Include *.md,*.txt -ErrorAction SilentlyContinue)) {
    Fail "源目录下没有 .md/.txt：$srcDir"
}
Log ("源文档目录：{0}  分类映射：{1}" -f $srcDir, $(if ($CategoriesJson) { $CategoriesJson } else { '无(全部=分类0)' }))

# ---------- 定位 manifest（来源清单，用于回填来源链接 & 供小标题脚本） ----------
# Section 模式清单在栏目外层 out/ 下；整目录模式可能就在目录内
$manifestSearch = if ($Section) { $InputDir } else { $srcDir }
$manifest = Get-ChildItem -LiteralPath $manifestSearch -File -Filter 'manifest_*.csv' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($manifest) { Log ('使用来源清单：{0}' -f $manifest.FullName) }
else { Log '未找到 manifest_*.csv：来源链接留空（热门知识 Top10 将退化为按标题归并，建议带上清单）' }

# ---------- 2) 切块 ----------
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$csv   = Join-Path $env:TEMP ("kb_oneclick_{0}_{1}.csv" -f $stamp, ([guid]::NewGuid().ToString('N').Substring(0, 6)))
Log '步骤2/5：切块生成导入 CSV（按 # 标题 / 段落聚成 150~500 字/条）……'
# 注：对 & $path 脚本做命名参数 splat 会退化成位置绑定，须显式命名传参。
#     PowerShell 脚本失败时会抛终止异常（其自身 $ErrorActionPreference=Stop）直接中断本脚本，
#     成功与否由下方 CSV 存在性校验兜底（$LASTEXITCODE 对脚本调用无效，勿用）。
& $builder `
    -InputDir $srcDir `
    -Output $csv `
    -MinChars $MinChars `
    -MaxChars $MaxChars `
    -CategoriesJson $(if ($CategoriesJson -and (Test-Path -LiteralPath $CategoriesJson)) { $CategoriesJson } else { '' }) `
    -Manifest $(if ($manifest) { $manifest.FullName } else { '' })
$rowCount = if (Test-Path -LiteralPath $csv) { @(Import-Csv -LiteralPath $csv).Count } else { 0 }
if ($rowCount -le 0) { Fail '切块结果为空（0 条），请检查 Markdown 是否有 # 标题或正文段落。' }
Log "切块完成：共 $rowCount 条 → $csv"

# ---------- 3) 可选小标题 ----------
$importCsv = $csv
if ($AddSubheadings) {
    if (-not $manifest) { Log '告警：未找到 manifest，跳过小标题生成（小标题按文件来源区分，无清单不适用）。' }
    elseif (-not $env:DEEPSEEK_API_KEY) { Fail '需要 -AddSubheadings 但未设置 DEEPSEEK_API_KEY（或在代码里 --key 指定）。' }
    else {
        $py = Resolve-Python
        Log '步骤3/5：调用 DeepSeek 为每条生成 6~14 字小标题……'
        $csv2 = Join-Path $env:TEMP ("kb_oneclick_{0}_{1}_subtitled.csv" -f $stamp, ([guid]::NewGuid().ToString('N').Substring(0, 6)))
        & $py $subheadings --csv $csv --manifest $manifest.FullName --output $csv2 --min-chars 20
        if ($LASTEXITCODE -ne 0) { Fail "add_subheadings.py 退出码 $LASTEXITCODE" }
        $importCsv = $csv2
        Log "小标题完成 → $csv2"
    }
}

if ($DryRun) {
    Write-Host "`n===== DryRun：以下是不调后端的预览 =====" -ForegroundColor Cyan
    Write-Host "CSV：$importCsv（行数 $rowCount）"
    Write-Host '前 8 行预览（标题 | 分类ID | 内容前40字）：'
    Import-Csv -LiteralPath $importCsv | Select-Object -First 8 | ForEach-Object {
        $head = ($_.内容 -replace '\s+', ' ')
        $head = $head.Substring(0, [Math]::Min(40, $head.Length))
        Write-Host ("  {0} | {1} | {2}" -f $_.标题, $_.分类ID, $head)
    }
    if ($KeepCsv) { Copy-Item -LiteralPath $importCsv -Destination (Join-Path $PWD "kb_oneclick_preview_$stamp.csv") -Force }
    exit 0
}

# ---------- 4) 登录并导入 ----------
Log '步骤4/5：登录后台并上传导入……'
$login = Invoke-RestMethod -Uri "$BackendUrl/api/auth/login" -Method Post `
    -ContentType 'application/json; charset=utf-8' `
    -Body (@{ username = $AdminUser; password = $AdminPass } | ConvertTo-Json) -TimeoutSec 30
if ($login.code -ne 200 -or -not $login.data.token) { Fail "登录失败：$($login.message)" }
$token = $login.data.token
$headers = @{ Authorization = "Bearer $token" }

$imp = Invoke-RestMethod -Uri "$BackendUrl/api/knowledge/chunk/import" -Method Post `
    -Headers $headers -Form @{ file = Get-Item -LiteralPath $importCsv } -TimeoutSec 600
if ($imp.code -ne 200) { Fail "导入失败：$($imp.message)" }
$d = $imp.data
Log ("导入结果：成功 {0} 条，失败 {1} 条。" -f $d.success, $d.fail)
if ($d.fail -gt 0 -and $d.errors) {
    Write-Host '  失败明细（前5条）：' -ForegroundColor Yellow
    $d.errors | Select-Object -First 5 | ForEach-Object { Write-Host "    - $_" -ForegroundColor Yellow }
}
Write-Host '  注意：success 只代表写入 MySQL；向量化失败会标记 vector_status=2，见下一步。' -ForegroundColor DarkGray

# ---------- 5) 修复 vector_status=2（可选） ----------
if ($RepairStatus2) {
    Log '步骤5/5：扫描并修复向量化失败的片段（vector_status=2）……'
    $rounds = 0
    while ($true) {
        $rounds++
        $failedIds = New-Object System.Collections.Generic.List[int]
        $pageNum = 1; $pageSize = 100
        while ($true) {
            $pg = Invoke-RestMethod -Uri "$BackendUrl/api/knowledge/chunk/page?pageNum=$pageNum&pageSize=$pageSize" -Method Get -Headers $headers -TimeoutSec 60
            $recs = @($pg.data.records)
            foreach ($r in $recs) { if ($r.vectorStatus -eq 2) { $failedIds.Add([int]$r.id) } }
            if ($recs.Count -lt $pageSize -or $pageNum * $pageSize -ge $pg.data.total) { break }
            $pageNum++
        }
        if ($failedIds.Count -eq 0) { Log '无 vector_status=2 的失败片段，全部向量就绪。'; break }
        Log ("第 {0} 轮：发现 {1} 条失败，逐条重建向量……" -f $rounds, $failedIds.Count)
        $ok = 0
        foreach ($id in $failedIds) {
            try {
                Invoke-RestMethod -Uri "$BackendUrl/api/knowledge/chunk/$id/reindex" -Method Post -Headers $headers -TimeoutSec 180 | Out-Null
                $ok++
            } catch { Write-Host "  reindex $id 失败：$($_.Exception.Message)" -ForegroundColor Yellow }
        }
        Log "本轮重建成功 $ok / $($failedIds.Count)。"
        if ($rounds -ge 2) { Log '已达最大修复轮数，仍有失败请到管理后台手动重试（常见为 SiliconFlow 限流）。'; break }
    }
}

Write-Host "`n===== 完成 =====" -ForegroundColor Green
Write-Host "本次共导入 $($d.success) 条（$importCsv）"
Write-Host '建议：到管理后台「知识库管理」抽查几条 vectorStatus=向量化成功、sourceTitle 正常。'
if ($KeepCsv) { Copy-Item -LiteralPath $importCsv -Destination (Join-Path $PWD "kb_oneclick_$stamp.csv") -Force; Write-Host "CSV 已保留到 $(Join-Path $PWD "kb_oneclick_$stamp.csv")" }
