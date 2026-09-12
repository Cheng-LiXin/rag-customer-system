# =============================================================================
# 一键验证（批次 A/B 的全部改动）—— 答辩/自测用
# -----------------------------------------------------------------------------
# 跑完输出一张 PASS/FAIL 汇总表，并给出真实测量值。任一项 FAIL → 退出码 1。
#
# 覆盖的检查：
#   [无需后端] 防护规则单元测试（含 L3 输出层独立验证）
#   [无需后端] 行内引用角标渲染回归（marked 扩展 + GFM 表格陷阱）
#   [需要后端] 环境探针：缓存旁路是否真的生效（不生效则后续指标全部失真）
#   [需要后端] Golden Set 跑分 + 红线断言（兜底率/误拒率/超纲拒答率/Recall）
#   [需要后端] 逐字保真断言（答案每句必须是召回片段的逐字子串）
#   [需要后端] 注入攻防 30 样本（拦截率 / 误杀率）
#
# 用法：
#   pwsh tools\verify_all.ps1
#   pwsh tools\verify_all.ps1 -SkipDemo      # 不动知识库（间接注入那几行会显示未拦截）
#   pwsh tools\verify_all.ps1 -SkipBackend   # 只跑不需要后端的单元检查
#   pwsh tools\verify_all.ps1 -Limit 8       # 跑分只跑前 8 题（快速冒烟）
#
# ⚠ 第 2~6 项（缓存旁路探针 / 跑分 / 逐字保真 / 攻防）要求后端带评估开关启动，
#   否则「缓存旁路生效」会 FAIL 并跳过后续 —— 那是设计如此（跑分必须每次真实检索）。
#   唯一工作环境是 Docker 全栈，开关要经 docker-compose 转发：
#     $env:RAG_EVAL_BYPASS='true'; $env:RAG_EVAL_MODE_OVERRIDE='true'
#     docker compose --profile full up -d backend
#     pwsh tools\verify_all.ps1                     # 默认打 http://127.0.0.1:8081
#     docker compose --profile full up -d backend   # 跑完去掉变量再重建，恢复生产行为
#
#   第 3 步载入的演示条目会在第 7 步自动清理（-SkipDemo 则既不载入也不清理）。
# =============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8081",
    [string]$PythonPath = "D:\Program\Python\Anaconda3\envs\myenv\python.exe",
    [switch]$SkipDemo,
    [switch]$SkipUnit,
    [switch]$SkipBackend,
    [int]$Limit = 0
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$script:results = New-Object System.Collections.Generic.List[object]

function Record($name, $passed, $detail) {
    $script:results.Add([pscustomobject]@{
        Check  = $name
        Result = $(if ($passed) { "PASS" } else { "FAIL" })
        Detail = $detail
    })
    $color = if ($passed) { "Green" } else { "Red" }
    Write-Host ("  [{0}] {1}  {2}" -f $(if ($passed) { "PASS" } else { "FAIL" }), $name, $detail) -ForegroundColor $color
}

function Section($title) {
    Write-Host ""
    Write-Host "===== $title =====" -ForegroundColor Cyan
}

# ---------------------------------------------------------------- 前置
Section "0. 环境前置"

if (-not (Test-Path $PythonPath)) {
    Record "Python 解释器" $false "找不到 $PythonPath（本机 python 是商店占位符，需全路径）"
    $PythonPath = $null
} else {
    Record "Python 解释器" $true $PythonPath
}

$backendUp = $false
try {
    $hello = Invoke-RestMethod -Uri "$BaseUrl/api/rag/hello" -TimeoutSec 5
    $backendUp = ($hello.code -eq 200)
} catch { $backendUp = $false }
Record "后端可达 ($BaseUrl)" $backendUp $(if ($backendUp) { "ok" } else { "未启动？后续需要后端的检查将跳过" })

# ---------------------------------------------------------------- 单元检查（无需后端）
if (-not $SkipUnit) {
    Section "1. 单元检查（不需后端）"

    # 1.1 防护规则（含 L3 输出层独立验证）
    # 不用 -q：需要它打印 "Tests run: N, Failures: F" 作为证据（-q 会把汇总行也吞掉）
    $unitOut = mvn test "-Dtest=InjectionGuardTest,IntentServiceTest" "-DfailIfNoTests=false" 2>&1
    $unitOk = ($LASTEXITCODE -eq 0)
    $m = $unitOut | Select-String -Pattern "Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$" | Select-Object -Last 1
    $summary = if ($m) { $m.ToString().Trim() } else { "exit=$LASTEXITCODE" }
    Record "防护规则单元测试 + 意图分类测试" $unitOk $summary
    if (-not $unitOk) { $unitOut | Select-Object -Last 25 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray } }

    # 1.2 行内引用渲染回归（GFM 表格会吞掉多余单元格的坑）
    Push-Location (Join-Path $root "frontend")
    $citeOut = node scripts/check-citations.mjs 2>&1
    $citeOk = ($LASTEXITCODE -eq 0)
    Pop-Location
    Record "行内引用角标渲染回归" $citeOk $(if ($citeOk) { "7 项断言全通过" } else { "见上方输出" })
    if (-not $citeOk) { $citeOut | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray } }
}

# ---------------------------------------------------------------- 后端相关检查
if (-not $SkipBackend -and $backendUp -and $PythonPath) {

    Section "2. 评估环境探针（缓存旁路）"
    $probeOut = & $PythonPath tools\eval\probe_env.py --base-url $BaseUrl 2>&1
    $probeOk = ($LASTEXITCODE -eq 0)
    $probeLine = ($probeOut | Select-String -Pattern "缓存旁路" | Select-Object -Last 1)
    Record "缓存旁路生效" $probeOk $(if ($probeLine) { $probeLine.ToString().Trim() } else { "exit=$LASTEXITCODE" })
    if (-not $probeOk) {
        $probeOut | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
        Write-Host "    后续跑分结果不可信，已跳过。" -ForegroundColor Yellow
    } else {

        if (-not $SkipDemo) {
            Section "3. 载入演示条目（幂等）"
            $loadOut = pwsh -NoProfile -File tools\guard\load_demo_kb.ps1 -BaseUrl $BaseUrl 2>&1
            $loadOk = ($LASTEXITCODE -eq 0) -and ($loadOut -match "导入完成")
            Record "注入演示条目就绪" $loadOk $(($loadOut | Select-String "导入完成|已清理" | ForEach-Object { $_.ToString().Trim() }) -join "；")
        }

        Section "4. Golden Set 跑分 + 红线断言"
        $evalArgs = @("tools\eval\run_eval.py", "--base-url", $BaseUrl, "--tag", "verify", "--assert")
        if ($Limit -gt 0) { $evalArgs += @("--limit", "$Limit") }
        $evalOut = & $PythonPath @evalArgs 2>&1
        $evalOk = ($LASTEXITCODE -eq 0)
        $evalOut | Select-String -Pattern "兜底率|误拒率|超纲拒答率|Recall|MRR|TTFT|\[PASS\]|\[FAIL\]" |
            ForEach-Object { Write-Host ("    " + $_.ToString().Trim()) -ForegroundColor DarkGray }
        Record "跑分红线断言" $evalOk $(if ($evalOk) { "全部达标" } else { "见上方 [FAIL]" })

        Section "5. 逐字保真断言"
        $verbArgs = @("tools\eval\verify_verbatim.py", "--base-url", $BaseUrl)
        if ($Limit -gt 0) { $verbArgs += @("--limit", "$Limit") }
        $verbOut = & $PythonPath @verbArgs 2>&1
        $verbOk = ($LASTEXITCODE -eq 0)
        $verbLine = ($verbOut | Select-String -Pattern "逐字保真率" | Select-Object -Last 1)
        Record "答案逐字来自原文" $verbOk $(if ($verbLine) { $verbLine.ToString().Trim() } else { "exit=$LASTEXITCODE" })
        if (-not $verbOk) { $verbOut | Select-Object -Last 15 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray } }

        Section "6. 注入攻防 30 样本"
        $guardOut = & $PythonPath tools\guard\run_guard_test.py --base-url $BaseUrl 2>&1
        $guardOk = ($LASTEXITCODE -eq 0)
        $guardOut | Select-String -Pattern "处置率|误杀率|有害内容泄露|未处置|被误杀" |
            ForEach-Object { Write-Host ("    " + $_.ToString().Trim()) -ForegroundColor DarkGray }
        Record "攻防样本符合预期" $guardOk $(if ($guardOk) { "见上方处置率/误杀率/泄露数" } else { "见上方漏放/泄露/误杀清单" })

        # 演示条目用完必须清掉：毒片段留在库里会污染检索结果（旧版只 load 不 cleanup）。
        # demo_cleanup 走 DELETE 接口，会连带删 PGVector 向量；库里没有 DEMO- 条目时也退出 0。
        if (-not $SkipDemo) {
            Section "7. 清理演示条目"
            $cleanOut = pwsh -NoProfile -File tools\guard\demo_cleanup.ps1 -BaseUrl $BaseUrl 2>&1
            $cleanOk = ($LASTEXITCODE -eq 0)
            Record "演示条目已清理" $cleanOk $(($cleanOut | Select-String "已删除|无需清理|没有找到" | ForEach-Object { $_.ToString().Trim() }) -join "；")
        }
    }
} elseif (-not $SkipBackend) {
    Section "2-6. 需要后端的检查"
    Write-Host "  已跳过（后端未启动或 Python 不可用）" -ForegroundColor Yellow
}

# ---------------------------------------------------------------- 汇总
Write-Host ""
Write-Host "==================== 验证汇总 ====================" -ForegroundColor Cyan
$script:results | Format-Table -AutoSize | Out-String | Write-Host
$failed = @($script:results | Where-Object { $_.Result -eq "FAIL" })
if ($failed.Count -eq 0) {
    Write-Host "全部通过 ✅" -ForegroundColor Green
} else {
    Write-Host ("有 {0} 项未通过 ❌" -f $failed.Count) -ForegroundColor Red
    $failed | ForEach-Object { Write-Host ("  - {0}: {1}" -f $_.Check, $_.Detail) -ForegroundColor Red }
}
Write-Host ""
Write-Host "提示：需要人眼确认的部分（脚本无法替代）：" -ForegroundColor Yellow
Write-Host "  1) 浏览器打开前端 → 问「2026 本科新生入学须知」→ 正文每句尾部应有可点击的 [n]，点击展开原条目"
Write-Host "  2) 管理后台 →「安全防护」→ 关开关 → 问「学费减免有什么承诺」应能看到越权承诺；开开关 → 被红条拦截"
Write-Host "  3) 该页下半部分应能看到刚才的命中记录（层级/规则/被污染片段/命中原文）"

exit $(if ($failed.Count -eq 0) { 0 } else { 1 })
