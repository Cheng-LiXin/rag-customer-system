# =============================================================================
# 清除「注入防护演示条目」（批次 B）
# -----------------------------------------------------------------------------
# 删除所有标题以 DEMO- 开头的知识片段。
#
# ⚠ 必须走 DELETE /api/knowledge/chunk/{id} 接口，**不要**直接 DELETE SQL：
#   接口会同步删除 PGVector 里的向量，直接删表会留下「幽灵向量」——
#   检索仍能命中一个数据库里已不存在的 chunk_id，且没有任何界面能看出问题。
#
# 用法（后端已启动）：
#   pwsh tools\guard\demo_cleanup.ps1
#   pwsh tools\guard\demo_cleanup.ps1 -BaseUrl http://127.0.0.1:8081
# =============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8081",
    [string]$AdminUser = "admin",
    [string]$AdminPass = $(if ($env:DEMO_ADMIN_PASSWORD) { $env:DEMO_ADMIN_PASSWORD } else { "Admin@Ysu2026" }),
    [string]$Prefix = "DEMO-",
    [switch]$WhatIf
)

$ErrorActionPreference = "Stop"

$loginBody = @{ username = $AdminUser; password = $AdminPass } | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($loginBody)) `
    -ContentType "application/json; charset=utf-8"
if ($login.code -ne 200) { throw "登录失败: $($login.message)" }
$token = $login.data.token

# 拉一页足够大的列表，按标题前缀筛（接口的 keyword 是 LIKE 匹配，这里再精确判一次前缀）
$page = Invoke-RestMethod -Uri "$BaseUrl/api/knowledge/chunk/page?pageNum=1&pageSize=500&keyword=$([uri]::EscapeDataString($Prefix))" `
    -Headers @{ Authorization = "Bearer $token" }
if ($page.code -ne 200) { throw "查询失败: $($page.message)" }

$targets = @($page.data.records | Where-Object { $_.title -and $_.title.StartsWith($Prefix) })
if ($targets.Count -eq 0) {
    Write-Host "没有找到标题以 '$Prefix' 开头的片段，无需清理。"
    exit 0
}

Write-Host "找到 $($targets.Count) 条演示片段："
$targets | ForEach-Object { Write-Host ("  id={0}  vectorStatus={1}  {2}" -f $_.id, $_.vectorStatus, $_.title) }

if ($WhatIf) {
    Write-Host ""
    Write-Host "-WhatIf：仅预览，未执行删除。" -ForegroundColor Yellow
    exit 0
}

$okCount = 0
foreach ($t in $targets) {
    $r = Invoke-RestMethod -Uri "$BaseUrl/api/knowledge/chunk/$($t.id)" -Method Delete `
        -Headers @{ Authorization = "Bearer $token" }
    if ($r.code -eq 200) { $okCount++ } else { Write-Host "  删除 id=$($t.id) 失败: $($r.message)" -ForegroundColor Red }
}
Write-Host ""
Write-Host "已删除 $okCount / $($targets.Count) 条（含 PGVector 向量）" -ForegroundColor Green
Write-Host "建议再跑一次攻防测试确认间接注入样本已恢复「未拦截」（毒片段已被清掉）："
Write-Host "  python tools\guard\run_guard_test.py   # C*/O* 应显示未拦截，这是符合预期的"
