# =============================================================================
# 全栈一键起（批次 C）—— 先预检，再 up
# -----------------------------------------------------------------------------
# 预检是刻意加的：compose 的变量插值用的是 `:-` 默认值（为了不破坏「只起依赖」那条
# 既有用法），所以 .env 缺失或密钥为空时**不会报错**，而是安静地起一个
# 「登录永远失败 / 问答永远报鉴权错」的系统 —— 那种问题排查起来极费时间。
# 这里提前把话说清楚。
#
# 用法：
#   pwsh tools\docker\up-full.ps1
#   pwsh tools\docker\up-full.ps1 -NoBuild     # 镜像已构建过，跳过 build
#   pwsh tools\docker\up-full.ps1 -CheckOnly   # 只预检，不起服务
# =============================================================================
param(
    [switch]$NoBuild,
    [switch]$CheckOnly
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$problems = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

function Read-DotEnv([string]$path) {
    $map = @{}
    if (-not (Test-Path $path)) { return $map }
    foreach ($line in Get-Content $path) {
        $t = $line.Trim()
        if ($t -eq "" -or $t.StartsWith("#")) { continue }
        $i = $t.IndexOf("=")
        if ($i -lt 1) { continue }
        $k = $t.Substring(0, $i).Trim()
        $v = $t.Substring($i + 1).Trim().Trim('"').Trim("'")
        $map[$k] = $v
    }
    return $map
}

Write-Host "===== 预检 =====" -ForegroundColor Cyan

# 1) .env
$envPath = Join-Path $root ".env"
$envMap = Read-DotEnv $envPath
if (-not (Test-Path $envPath)) {
    $problems.Add("缺少 .env。先执行： copy .env.example .env   然后填写密钥")
    Write-Host "  [FAIL] 未找到 .env" -ForegroundColor Red
} else {
    Write-Host "  [ OK ] .env 存在" -ForegroundColor Green
}

# 2) 必填密钥
#    区分「致命」与「警告」：空的 API Key 会让问答直接报鉴权错（致命）；
#    而仓库里的开发默认 JWT 密钥是**能用**的（登录正常），只是不该上公网（警告）。
$jwt = $envMap["JWT_SECRET"]
if ([string]::IsNullOrWhiteSpace($jwt)) {
    $problems.Add("JWT_SECRET 未填写 —— 登录会失败")
    Write-Host "  [FAIL] JWT_SECRET 未填写" -ForegroundColor Red
} elseif ($jwt.Length -lt 32) {
    $problems.Add("JWT_SECRET 长度不足 32（当前 $($jwt.Length)）")
    Write-Host "  [FAIL] JWT_SECRET 长度不足 32" -ForegroundColor Red
} elseif ($jwt -match "change-me|local-dev") {
    $warnings.Add("JWT_SECRET 用的是仓库里的开发默认值 —— 本机演示没问题，公网部署前请换成随机值（openssl rand -base64 48）")
    Write-Host "  [WARN] JWT_SECRET 是开发默认值（可用，但不适合公网）" -ForegroundColor Yellow
} else {
    Write-Host "  [ OK ] JWT_SECRET 已配置（长度 $($jwt.Length)）" -ForegroundColor Green
}

foreach ($k in @("DEEPSEEK_API_KEY", "SILICONFLOW_API_KEY")) {
    $v = $envMap[$k]
    if ([string]::IsNullOrWhiteSpace($v) -or $v -match "xxx") {
        $problems.Add("$k 未填写（占位值）—— 问答与向量化会报鉴权错")
        Write-Host "  [FAIL] $k 未填写" -ForegroundColor Red
    } elseif ($v.Length -lt 8) {
        $problems.Add("$k 长度异常（当前 $($v.Length)）")
        Write-Host "  [FAIL] $k 长度异常" -ForegroundColor Red
    } else {
        Write-Host "  [ OK ] $k 已配置（长度 $($v.Length)）" -ForegroundColor Green
    }
}

# 3) MySQL 初始化脚本
$initdb = Join-Path $root "tools\docker\initdb"
$sqls = @(Get-ChildItem -Path $initdb -Filter "*.sql" -File -ErrorAction SilentlyContinue)
if ($sqls.Count -eq 0) {
    $problems.Add("tools/docker/initdb 下没有 .sql —— 容器起来会是个空库。见该目录 README")
    Write-Host "  [FAIL] 缺 MySQL 初始化脚本" -ForegroundColor Red
} else {
    Write-Host ("  [ OK ] 初始化脚本：" + (($sqls | ForEach-Object { $_.Name }) -join ", ")) -ForegroundColor Green
}

# 4) 端口占用
$webPort = if ($envMap["WEB_PORT"]) { [int]$envMap["WEB_PORT"] } else { 8081 }
$myPort = if ($envMap["MYSQL_PORT"]) { [int]$envMap["MYSQL_PORT"] } else { 3307 }
foreach ($p in @($webPort, $myPort)) {
    $busy = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($busy) {
        $problems.Add("端口 $p 已被占用（pid $($busy.OwningProcess -join ',')）")
        Write-Host "  [FAIL] 端口 $p 被占用" -ForegroundColor Red
    } else {
        Write-Host "  [ OK ] 端口 $p 空闲" -ForegroundColor Green
    }
}

if ($problems.Count -gt 0) {
    Write-Host ""
    Write-Host "预检未通过，已停止（没有起任何服务）：" -ForegroundColor Red
    $problems | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}

if ($warnings.Count -gt 0) {
    Write-Host ""
    Write-Host "警告（不阻塞启动）：" -ForegroundColor Yellow
    $warnings | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
}

Write-Host ""
Write-Host "预检通过 ✅" -ForegroundColor Green
if ($CheckOnly) { exit 0 }

Write-Host ""
Write-Host "===== 启动全栈 =====" -ForegroundColor Cyan
$composeArgs = @("compose", "--profile", "full", "up", "-d")
if (-not $NoBuild) { $composeArgs += "--build" }
& docker @composeArgs
if ($LASTEXITCODE -ne 0) { throw "docker compose 启动失败，退出码 $LASTEXITCODE" }

Write-Host ""
Write-Host "已启动。等待后端健康..." -ForegroundColor Cyan
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 5
    try {
        $r = Invoke-RestMethod -Uri "http://127.0.0.1:$webPort/api/rag/hello" -TimeoutSec 5
        if ($r.code -eq 200) { $ready = $true; break }
    } catch { }
}

Write-Host ""
if ($ready) {
    Write-Host "全栈就绪 ✅" -ForegroundColor Green
    Write-Host "  用户端 / 管理后台： http://localhost:$webPort" -ForegroundColor Green
} else {
    Write-Host "后端尚未就绪（可能仍在构建/启动）。排查：" -ForegroundColor Yellow
    Write-Host "  docker compose --profile full ps"
    Write-Host "  docker compose --profile full logs --tail 80 backend"
    Write-Host "  首次构建需下载 Maven/Node 依赖，可能要几分钟。"
}
Write-Host ""
Write-Host "演示完成后：docker compose --profile full down    （不要加 -v，会删向量数据）" -ForegroundColor Yellow
