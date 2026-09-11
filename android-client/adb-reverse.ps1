# 真机 USB 联调：把手机的 127.0.0.1:8080 反向映射到电脑的 8080（App 默认地址即指向它）。
# 拔插 USB / 重启 adb / 重启电脑后需重新执行本脚本。
# 用法：  pwsh -File .\android-client\adb-reverse.ps1
$ErrorActionPreference = "Stop"

$adb = "C:\Users\19832\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb -or -not (Test-Path $adb)) {
    Write-Host "找不到 adb：请安装 Android SDK platform-tools 或把 adb 加入 PATH" -ForegroundColor Red
    exit 1
}

$dev = & $adb devices | Select-String "device$"
if (-not $dev) {
    Write-Host "未检测到已授权设备，请确认 USB 已连接且已允许调试（adb devices）" -ForegroundColor Red
    exit 1
}

& $adb reverse tcp:8080 tcp:8080 | Out-Null
Write-Host "adb reverse 映射已建立：" -ForegroundColor Green
& $adb reverse --list

# 手机端自测：经映射访问后端 /api/chat/history（该接口 permitAll，未登录返回 200 + 空列表）
$code = & $adb shell "curl -s -m 6 -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/chat/history"
if ($code -eq "200") {
    Write-Host "手机 -> 127.0.0.1:8080 连通 OK（http_code=200）" -ForegroundColor Green
} else {
    Write-Host "手机 -> 127.0.0.1:8080 返回 http_code=$code（检查电脑后端是否已启动）" -ForegroundColor Yellow
}
