# =============================================================================
# 导入「注入防护演示条目」（批次 B）
# -----------------------------------------------------------------------------
# 把 tools/guard/demo_kb_entries.csv 导入知识库并向量化，供间接注入演示使用。
# 条目标题以 DEMO- 开头；演示结束后用 demo_cleanup.ps1 一键清除。
#
# 幂等：导入前会先删除已有的 DEMO- 条目。这一步是必须的 ——
# 知识库导入接口**只增不删不去重**，重复执行会留下多份
# 毒片段，既污染检索结果，也让「清理干净」变得不可靠。
#
# 用法（后端已启动）：
#   pwsh tools\guard\load_demo_kb.ps1
#   pwsh tools\guard\load_demo_kb.ps1 -BaseUrl http://127.0.0.1:8081
# =============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8081",
    [string]$AdminUser = "admin",
    [string]$AdminPass = $(if ($env:DEMO_ADMIN_PASSWORD) { $env:DEMO_ADMIN_PASSWORD } else { "Admin@Ysu2026" }),
    [string]$Prefix = "DEMO-"
)

$ErrorActionPreference = "Stop"
$csv = Join-Path $PSScriptRoot "demo_kb_entries.csv"
if (-not (Test-Path $csv)) { throw "找不到演示条目文件: $csv" }

# 1) 管理员登录拿 JWT
$loginBody = @{ username = $AdminUser; password = $AdminPass } | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($loginBody)) `
    -ContentType "application/json; charset=utf-8"
if ($login.code -ne 200) { throw "登录失败: $($login.message)" }
$auth = @{ Authorization = "Bearer $($login.data.token)" }
Write-Host "已登录 $AdminUser"

# 2) 幂等：先清掉已有的 DEMO- 条目（走接口删，会同步删 PGVector 向量）
$page = Invoke-RestMethod -Uri "$BaseUrl/api/knowledge/chunk/page?pageNum=1&pageSize=500&keyword=$([uri]::EscapeDataString($Prefix))" `
    -Headers $auth
$existing = @($page.data.records | Where-Object { $_.title -and $_.title.StartsWith($Prefix) })
foreach ($e in $existing) {
    Invoke-RestMethod -Uri "$BaseUrl/api/knowledge/chunk/$($e.id)" -Method Delete -Headers $auth | Out-Null
}
if ($existing.Count -gt 0) {
    Write-Host "已清理上一次遗留的 $($existing.Count) 条演示片段（保证幂等）"
}

# 3) 上传演示条目 CSV（multipart，服务端会同步向量化）
$resp = Invoke-RestMethod -Uri "$BaseUrl/api/knowledge/chunk/import" -Method Post `
    -Headers $auth -Form @{ file = Get-Item $csv }
if ($resp.code -ne 200) { throw "导入失败: $($resp.message)" }

Write-Host ("导入完成：success={0} fail={1}" -f $resp.data.success, $resp.data.fail)
if ($resp.data.errors -and $resp.data.errors.Count -gt 0) {
    Write-Host "错误明细：" -ForegroundColor Yellow
    $resp.data.errors | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
}
Write-Host ""
Write-Host "下一步：等向量化完成（vector_status=1）后跑攻防测试" -ForegroundColor Cyan
Write-Host "  python tools\guard\run_guard_test.py" -ForegroundColor Cyan
Write-Host "演示结束后务必清理（否则演示条目会一直留在知识库里）：" -ForegroundColor Yellow
Write-Host "  pwsh tools\guard\demo_cleanup.ps1" -ForegroundColor Yellow
