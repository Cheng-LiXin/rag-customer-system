# =============================================================================
# JMeter 阶梯压测（批次 F）
# -----------------------------------------------------------------------------
# 运行器按优先级自动选择，都不需要你手工装东西：
#   ① -JmeterHome 指定的本地 JMeter
#   ② tools/loadtest/.jmeter/ 下已下载的 JMeter（会自动下载，约 86MB，走清华镜像）
#   ③ Docker 镜像（若本地已有）
#
# ⚠ 压测口径（不按这个来，数据就是废的）：
#   A 轮「缓存路径」：先 -Warmup 预热（让 qa:cache 填满），再重复打同一批题。
#                     测的是服务端并发能力（基本不含大模型时延），这才是"系统吞吐"的合理口径。
#   B 轮「冷路径」：换一批没问过的问题，小并发（≤10）跑，测真实端到端时延与错误率。
#                     绝不要 100 并发打冷路径 —— DeepSeek 会限流，数据无意义且烧钱。
#
# 用法（注意 -Warmup 是**开关**，-Tag warmup 只是给结果起名，两者别搞混）：
#   pwsh tools\loadtest\run.ps1 -Warmup -Loops 48                            # 预热（强制单线程）
#   pwsh tools\loadtest\run.ps1 -Tag A -Threads 20 -Ramp 60 -Loops 5         # A 轮：缓存路径
#   pwsh tools\loadtest\run.ps1 -Tag A2 -Threads 50 -Ramp 120 -Loops 5       # 加大并发看拐点
#   pwsh tools\loadtest\run.ps1 -Tag B -Threads 5 -Ramp 15 -Loops 5          # B 轮：冷路径（先清 qa:cache:*）
# =============================================================================
param(
    [string]$HostName = "127.0.0.1",
    [int]$Port = 8080,
    [int]$Threads = 20,
    [int]$Ramp = 60,
    [int]$Loops = 5,
    [string]$Tag = "run",
    [string]$JmeterHome = "",
    [string]$JmeterVersion = "5.6.3",
    [string]$JmeterMirror = "https://mirrors.tuna.tsinghua.edu.cn/apache/jmeter/binaries",
    [switch]$Warmup
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root

# ---------------------------------------------------------------- 解析 JMeter
function Resolve-Jmeter {
    if ($JmeterHome) {
        $c = Join-Path $JmeterHome "bin\jmeter.bat"
        if (Test-Path $c) { return $c }
        throw "-JmeterHome 下找不到 bin\jmeter.bat：$JmeterHome"
    }
    $local = Join-Path $root ".jmeter\apache-jmeter-$JmeterVersion\bin\jmeter.bat"
    if (Test-Path $local) { return $local }

    Write-Host "未找到本地 JMeter，自动下载 apache-jmeter-$JmeterVersion ..." -ForegroundColor Yellow
    $dest = Join-Path $root ".jmeter"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $zip = Join-Path $dest "apache-jmeter-$JmeterVersion.zip"
    if (-not (Test-Path $zip)) {
        Invoke-WebRequest -Uri "$JmeterMirror/apache-jmeter-$JmeterVersion.zip" -OutFile $zip -TimeoutSec 600
    }
    Expand-Archive -Path $zip -DestinationPath $dest -Force
    if (-not (Test-Path $local)) { throw "解压后仍未找到 $local" }
    return $local
}

$jmeter = Resolve-Jmeter

# 预热模式在**打印之前**就改写参数，否则表头显示的是没生效的旧值（第一版就踩了这个）
$effectiveTag = $Tag
$modeNote = ""
if ($Warmup) {
    $Threads = 1; $Ramp = 1
    $effectiveTag = "$Tag-warmup"
    $modeNote = "预热：单线程顺序跑完一整轮问题，把 qa:cache 填满"
}

Write-Host "===== JMeter 压测 =====" -ForegroundColor Cyan
Write-Host "  运行器    : $jmeter"
Write-Host "  目标      : http://${HostName}:${Port}"
Write-Host "  线程/爬升 : $Threads / ${Ramp}s"
Write-Host "  循环次数  : $Loops"
Write-Host "  预计请求  : $($Threads * $Loops) 次"
Write-Host "  标签      : $effectiveTag"
if ($modeNote) {
    Write-Host "  模式      : $modeNote" -ForegroundColor Cyan
} elseif ($Loops -gt 10) {
    Write-Host "  ⚠ 冷路径大循环：DeepSeek 很可能限流，数据会失真" -ForegroundColor Yellow
}

$jtlDir = Join-Path $root "results"
$reportParent = Join-Path $root "report"
# JMeter 的 -o 只会创建**最后一级**目录，父目录不存在会直接报错退出
New-Item -ItemType Directory -Force -Path $jtlDir | Out-Null
New-Item -ItemType Directory -Force -Path $reportParent | Out-Null
$jtl = Join-Path $jtlDir "$effectiveTag.jtl"
if (Test-Path $jtl) { Remove-Item -Force $jtl }
$reportDir = Join-Path $reportParent $effectiveTag
if (Test-Path $reportDir) { Remove-Item -Recurse -Force $reportDir }

Write-Host ""
# < NUL：jmeter.bat 出错时会 pause，非交互环境下会挂住
& cmd /c "`"$jmeter`" -n -t rag-chat.jmx -l `"$jtl`" -e -o `"$reportDir`" -Jhost=$HostName -Jport=$Port -Jthreads=$Threads -Jramp=$Ramp -Jloops=$Loops < NUL" 2>&1 |
    Where-Object { $_ -notmatch "StatusConsoleListener" -and $_ -notmatch "Press any key" }
$code = $LASTEXITCODE

Write-Host ""
if ($code -ne 0) {
    Write-Host "JMeter 退出码 $code —— 断言失败率过高时会非零退出，看上面输出定位" -ForegroundColor Red
} else {
    Write-Host "压测完成 ✅" -ForegroundColor Green
}

# ---------------------------------------------------------------- 就地汇总
if (Test-Path $jtl) {
    Write-Host "明细：$jtl"
    $rows = Import-Csv $jtl
    if ($rows.Count -gt 0) {
        $ok = @($rows | Where-Object { $_.success -eq 'true' })
        $times = @($ok | ForEach-Object { [double]$_.elapsed } | Sort-Object)
        function Pctl($q) {
            if ($times.Count -eq 0) { return 0 }
            return $times[[Math]::Min($times.Count - 1, [int][Math]::Floor($q * $times.Count))]
        }
        $stamps = @($rows | ForEach-Object { [int64]$_.timeStamp })
        $span = [Math]::Max(0.001, (($stamps | Measure-Object -Maximum).Maximum - ($stamps | Measure-Object -Minimum).Minimum) / 1000.0)
        Write-Host ""
        Write-Host "===== 汇总 =====" -ForegroundColor Cyan
        Write-Host ("  样本数     : {0}（成功 {1}，失败 {2}）" -f $rows.Count, $ok.Count, ($rows.Count - $ok.Count))
        Write-Host ("  错误率     : {0:P2}" -f (($rows.Count - $ok.Count) / [Math]::Max(1, $rows.Count)))
        Write-Host ("  吞吐(TPS)  : {0:N1}" -f ($rows.Count / $span))
        Write-Host ("  响应时间   : p50 {0:N0}ms  p90 {1:N0}ms  p95 {2:N0}ms  max {3:N0}ms" -f (Pctl 0.50), (Pctl 0.90), (Pctl 0.95), (($times | Measure-Object -Maximum).Maximum))
        Write-Host ""
        Write-Host "  A 轮（缓存路径）的响应时间主要反映服务端并发能力；" -ForegroundColor Yellow
        Write-Host "  B 轮（冷路径）才是真实端到端时延，但受上游大模型限流影响，样本要小。" -ForegroundColor Yellow
    }
    Write-Host ""
    Write-Host "HTML 报告：$reportDir\index.html"
}
