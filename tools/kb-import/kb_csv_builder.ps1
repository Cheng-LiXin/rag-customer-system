#requires -Version 7
<#
.SYNOPSIS
  把源知识文档(.md/.txt)按标题/段落自动切块，生成系统「知识库 CSV 导入」文件。

.DESCRIPTION
  对应后端 KnowledgeServiceImpl.toCsv/importCsv 的格式：
    列顺序: 标题,分类ID,内容,关键词,来源链接
    第 1 行固定为表头（导入时总是被跳过）
    分类ID 必须是 knowledge_category 表中已存在的数字 id，0 = 不分类
    关键词本脚本留空；「来源链接」在给定 -Manifest（web-collect 采集清单）时按文件名自动回填，
    否则留空，可自行用 Excel 补
  切片策略：
    - 识别 Markdown 的 # 级标题作为新一条知识的边界，标题即该条的「标题」；
    - 正文按空行拆成段落，再把段落按字数预算(MinChars~MaxChars)聚成一条内容；
    - 无标题的纯文本文件：整个文件按段落切，标题取文件名。
  输出为 UTF-8(BOM) CSV，Excel 可直接打开（后端解析时自动剥 BOM）。

.EXAMPLE
  # 基本用法：把 tools\kb-import\input 下所有 .md/.txt 切成一条 CSV
  ./kb_csv_builder.ps1

  # 指定分类：按输入根目录下的子文件夹名映射到分类ID
  ./kb_csv_builder.ps1 -InputDir .\docs -Output .\kb.csv -CategoryId 0 -CategoriesJson .\categories.json

  # 子文件夹名 -> 分类ID 映射示例 categories.json:  {"教务服务":1,"学工":2}

  # 回填来源链接：不传 -Manifest 时自动找输入目录下的 manifest_*.csv；
  #   也可显式指定清单（列: 栏目,分类ID,文章标题,字数,来源URL,本地文件）
  ./kb_csv_builder.ps1 -InputDir ..\web-collect\out -Output .\kb.csv -Manifest ..\web-collect\out\manifest_20260902_155215.csv
#>
[CmdletBinding()]
param(
    # 源文档目录（含子目录递归）
    [string]$InputDir = (Join-Path $PSScriptRoot 'input'),

    # 输出 CSV 路径（默认在脚本同级生成 knowledge_import.csv）
    [string]$Output = '',

    # 无映射时使用的默认分类ID
    [int]$CategoryId = 0,

    # 可选：JSON 文件，{"子文件夹名": 分类ID}，按文件所在子文件夹名取分类
    [string]$CategoriesJson = '',

    # 单条内容最小/最大字符预算（用于把长文档切成若干条）
    [int]$MinChars = 150,
    [int]$MaxChars = 500,

    # 关闭跨文件的内容完全去重（默认开启：内容重复的行只留第一条）
    [switch]$NoDedup,

    # 可选：web-collect 采集清单 manifest_*.csv（列: 栏目,分类ID,文章标题,字数,来源URL,本地文件）。
    # 给定后按「栏目/文件名」回填每条的「来源链接」列；不传则自动检测输入目录下的 manifest_*.csv，
    # 都没有则来源链接留空（不影响导入，只是热门知识 Top10 会退化为按标题归并）。
    [string]$Manifest = ''
)

$ErrorActionPreference = 'Stop'

function Get-CsvField {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) { $Value = '' }
    if ($Value -match '[,"\r\n]') {
        return '"' + $Value.Replace('"', '""') + '"'
    }
    return $Value
}

function Read-TextFileRobust {
    param([string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    # 先按 UTF-8 严格解码（能识别 BOM）；失败（多为 GBK/GB2312 中文文本）则退回 GB18030
    $strict = [System.Text.UTF8Encoding]::new($false, $true)
    try { return $strict.GetString($bytes) }
    catch {
        $gb = [System.Text.Encoding]::GetEncoding(54936)  # GB18030
        return $gb.GetString($bytes)
    }
}

function Get-TrimmedTitle {
    param([AllowNull()][string]$Heading, [string]$Fallback)
    if ([string]::IsNullOrWhiteSpace($Heading)) { return $Fallback }
    $t = $Heading -replace '^\s*#+\s*', ''
    $t = $t.Trim().TrimEnd('#').Trim()
    $t = $t -replace '\*\*(.+?)\*\*', '$1'   # 去 Markdown 加粗
    $t = $t -replace '[`*_~]', ''             # 去行内代码/斜体残留
    $t = ($t -replace '\s+', ' ').Trim()
    if ($t.Length -gt 200) { $t = $t.Substring(0, 200) }
    if ([string]::IsNullOrWhiteSpace($t)) { return $Fallback }
    return $t
}

<#
  把一个文档的行数组切成一列 chunk：
  [pscustomobject]@{ Title=..; Content=.. }
#>
function ConvertTo-KbChunks {
    param([string[]]$Lines, [string]$DocTitle, [int]$MinC, [int]$MaxC)

    # 1) 按 Markdown ATX 标题切「块」
    $blocks = [System.Collections.Generic.List[object]]::new()
    $curTitle = $DocTitle
    $curLines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $Lines) {
        if ($line -match '^#{1,6}\s+(.+)$') {
            if ($curLines.Count -gt 0) {
                $blocks.Add([pscustomobject]@{ Title = $curTitle; Lines = $curLines.ToArray() })
                $curLines.Clear()
            }
            $curTitle = Get-TrimmedTitle -Heading $Matches[1] -Fallback $DocTitle
        }
        else {
            $curLines.Add($line)
        }
    }
    if ($curLines.Count -gt 0) {
        $blocks.Add([pscustomobject]@{ Title = $curTitle; Lines = $curLines.ToArray() })
    }

    # 2) 每个块：过滤噪音行 -> 按空行聚成段落 -> 按字数预算聚成一条
    $result = [System.Collections.Generic.List[object]]::new()
    foreach ($block in $blocks) {
        $paras = [System.Collections.Generic.List[string]]::new()
        $sb = [System.Text.StringBuilder]::new()
        foreach ($line in $block.Lines) {
            if ($line -match '^\s*$') {
                if ($sb.Length -gt 0) {
                    $paras.Add($sb.ToString().Trim())
                    $null = $sb.Clear()
                }
            }
            elseif ($line -match '^\s*(?:!\[[^\]]*\]\([^)]*\)|[-*_]{3,}\s*$|<!--.*-->|```|~~~)\s*$') {
                continue  # 图片 / 分隔线 / HTML 注释 / 代码围栏 整行忽略
            }
            else {
                [void]$sb.Append($line.TrimEnd())
                [void]$sb.Append("`n")
            }
        }
        if ($sb.Length -gt 0) {
            $paras.Add($sb.ToString().Trim())
            $null = $sb.Clear()
        }
        if ($paras.Count -eq 0) { continue }  # 标题下面没有正文，跳过

        $buf = [System.Collections.Generic.List[string]]::new()
        $bufLen = 0
        foreach ($p in $paras) {
            if ($buf.Count -gt 0 -and $bufLen -ge $MinC -and ($bufLen + $p.Length) -gt $MaxC) {
                $result.Add([pscustomobject]@{ Title = $block.Title; Content = ($buf -join "`n") })
                $buf.Clear(); $bufLen = 0
            }
            $buf.Add($p); $bufLen += $p.Length
        }
        if ($buf.Count -gt 0) {
            $result.Add([pscustomobject]@{ Title = $block.Title; Content = ($buf -join "`n") })
        }
    }
    return , $result.ToArray()
}

# ============================== 主流程 ==============================

$inDir = [System.IO.Path]::GetFullPath($InputDir)
if (-not (Test-Path $inDir)) {
    Write-Error "输入目录不存在: $inDir"
    exit 1
}
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $PSScriptRoot 'knowledge_import.csv'
}
$outDir = Split-Path -Parent $Output
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

# 子文件夹名 -> 分类ID 映射
$catMap = @{}
if (-not [string]::IsNullOrWhiteSpace($CategoriesJson) -and (Test-Path $CategoriesJson)) {
    $j = Get-Content -Raw -Path $CategoriesJson | ConvertFrom-Json
    foreach ($prop in $j.PSObject.Properties) {
        $catMap[$prop.Name] = [int]$prop.Value
    }
}

# 采集清单(manifest_*.csv) -> 来源链接查找表：优先按「栏目/文件名」，栏目对不上时回退按纯文件名
$urlByFull = @{}
$urlByName = @{}
if ([string]::IsNullOrWhiteSpace($Manifest)) {
    $autoManifest = Get-ChildItem -Path $inDir -Filter 'manifest_*.csv' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -ne $autoManifest) { $Manifest = $autoManifest.FullName }
}
if (-not [string]::IsNullOrWhiteSpace($Manifest)) {
    if (-not (Test-Path $Manifest)) {
        Write-Warning ("采集清单不存在，来源链接将留空: {0}" -f $Manifest)
    }
    else {
        $mfRows = Import-Csv -Path $Manifest
        foreach ($row in $mfRows) {
            $mfFile = [string]$row.本地文件
            $mfUrl  = [string]$row.来源URL
            if ([string]::IsNullOrWhiteSpace($mfFile) -or [string]::IsNullOrWhiteSpace($mfUrl)) { continue }
            $mfColumn = [string]$row.栏目
            if (-not [string]::IsNullOrWhiteSpace($mfColumn)) {
                $urlByFull["{0}/{1}" -f $mfColumn.Trim(), $mfFile] = $mfUrl.Trim()
            }
            $urlByName[$mfFile] = $mfUrl.Trim()
        }
        Write-Host ("载入采集清单 {0} 条记录 -> 来源链接可回填" -f $mfRows.Count)
    }
}

$files = Get-ChildItem -Path $inDir -Recurse -File |
    Where-Object { $_.Extension -in '.md', '.markdown', '.txt' } |
    Sort-Object FullName

if ($files.Count -eq 0) {
    Write-Error "目录下没有 .md / .markdown / .txt 文件: $inDir"
    exit 1
}

$seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$rows = [System.Collections.Generic.List[object]]::new()
$dupes = 0
$emptyChunks = 0
$tooLong = 0
$withSrc = 0

foreach ($file in $files) {
    $raw = Read-TextFileRobust -Path $file.FullName
    $lines = $raw -split '\r?\n'
    $docTitle = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)

    # 取相对于输入根目录的第一层子文件夹名用于分类映射
    $rel = $file.FullName.Substring($inDir.Length).TrimStart('\', '/')
    $segments = $rel -split '[\\/]'
    $folderName = if ($segments.Length -gt 1) { $segments[0] } else { '' }
    $cat = if ($catMap.ContainsKey($folderName)) { $catMap[$folderName] } else { $CategoryId }

    # 来源链接：先精匹配「栏目/文件名」，匹配不上回退到纯文件名（栏目名不一致也能命中）
    $srcUrl = ''
    if (-not [string]::IsNullOrWhiteSpace($folderName)) {
        $fullKey = "{0}/{1}" -f $folderName, $file.Name
        if ($urlByFull.ContainsKey($fullKey)) { $srcUrl = $urlByFull[$fullKey] }
    }
    if ([string]::IsNullOrWhiteSpace($srcUrl) -and $urlByName.ContainsKey($file.Name)) {
        $srcUrl = $urlByName[$file.Name]
    }

    $chunks = ConvertTo-KbChunks -Lines $lines -DocTitle $docTitle -MinC $MinChars -MaxC $MaxChars
    foreach ($chunk in $chunks) {
        $content = $chunk.Content.Trim()
        if ($content.Length -eq 0) { $emptyChunks++; continue }
        if (-not $NoDedup) {
            if (-not $seen.Add($content)) { $dupes++; continue }
        }
        if ($content.Length -gt $MaxChars) { $tooLong++ }
        if (-not [string]::IsNullOrWhiteSpace($srcUrl)) { $withSrc++ }
        $rows.Add([pscustomobject]@{ Title = $chunk.Title; CategoryId = $cat; Content = $content; SourceUrl = $srcUrl })
    }
    Write-Host ("读取 {0}  -> {1} 条" -f $file.FullName.Substring($inDir.Length), $chunks.Count)
}

# 写 CSV（UTF-8 BOM，行分隔 CRLF，RFC4180 转义）
$sb = [System.Text.StringBuilder]::new()
[void]$sb.Append('标题,分类ID,内容,关键词,来源链接')
[void]$sb.Append("`r`n")
foreach ($r in $rows) {
    [void]$sb.Append((Get-CsvField $r.Title)).Append(',')
    [void]$sb.Append((Get-CsvField ([string]$r.CategoryId))).Append(',')
    [void]$sb.Append((Get-CsvField $r.Content)).Append(',')
    [void]$sb.Append(',').Append((Get-CsvField $r.SourceUrl)).Append("`r`n")   # 关键词留空；来源链接按清单回填
}
$enc = [System.Text.UTF8Encoding]::new($true)
[System.IO.File]::WriteAllText($Output, $sb.ToString(), $enc)

Write-Host ''
Write-Host ('完成: 共 {0} 个文件 -> {1} 条知识 (去除重复 {2}, 跳过空块 {3}, 单条超 {4} 字 {5}, 带来源链接 {6})' -f `
        $files.Count, $rows.Count, $dupes, $emptyChunks, $MaxChars, $tooLong, $withSrc)
Write-Host ('输出: {0}' -f $Output)
Write-Host '下一步用管理员 token 调 POST /api/knowledge/chunk/import 上传该 CSV。'
