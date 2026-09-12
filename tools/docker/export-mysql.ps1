# =============================================================================
# 把本机 MySQL 的 rag_customer 导出为容器初始化脚本（批次 C）
# -----------------------------------------------------------------------------
# 用途：让「Compose 全栈」起来后数据与你现在本机跑的完全一致 ——
#       尤其是知识库那 159 条片段与它们在本机 MySQL 里的状态，不必重新导入。
#
# 产物：tools/docker/initdb/01_rag_customer.sql
#       该目录被 compose 挂到 MySQL 容器的 /docker-entrypoint-initdb.d，
#       **仅在 MySQL 数据卷为空时的首次启动**执行一次。
#
# 用法：
#   pwsh tools\docker\export-mysql.ps1
#   pwsh tools\docker\export-mysql.ps1 -Password "你的口令"
#
# ⚠ 只要 mysql-data 卷已存在（即容器起过一次），再改这个 SQL 也不会重跑。
#   要重新初始化：docker compose --profile full down
#                 docker volume rm rag-customer-system_mysql-data   # ★ 只删 MySQL 卷
#                 docker compose --profile full up -d
#   千万不要用 `down -v` 全清 —— 那会连 pg-data（159 条向量）一起删掉。
# =============================================================================
param(
    [string]$MysqlDumpExe = "D:\mysql-8.4.11-winx64\bin\mysqldump.exe",
    [string]$OutDir = (Join-Path $PSScriptRoot "initdb"),
    [string]$User = "root",
    [string]$Password = "123456",
    [string]$Database = "rag_customer"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $MysqlDumpExe)) {
    throw "找不到 mysqldump：$MysqlDumpExe（本机 MySQL 8.4 的 bin 目录，按需用 -MysqlDumpExe 指定）"
}
if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}

$outFile = Join-Path $OutDir "01_$Database.sql"
Write-Host "正在导出 $Database → $outFile"

# --databases：让脚本自带 CREATE DATABASE / USE，容器首次初始化可直接执行
# --single-transaction：InnoDB 一致性快照，不锁表
# --set-gtid-purged=OFF：MySQL 8 默认会写 GTID 语句，在未开 GTID 的实例上导入会报错
# --no-tablespaces：避免要求 PROCESS 权限
& $MysqlDumpExe `
    "--user=$User" "--password=$Password" `
    --databases $Database `
    --single-transaction `
    --default-character-set=utf8mb4 `
    --set-gtid-purged=OFF `
    --no-tablespaces `
    --result-file=$outFile 2>&1 | Where-Object { $_ -notmatch 'Using a password' }

if ($LASTEXITCODE -ne 0) {
    throw "mysqldump 失败，退出码 $LASTEXITCODE"
}

$size = (Get-Item $outFile).Length
Write-Host ("导出完成：{0:N0} 字节" -f $size) -ForegroundColor Green

# 顺手报一下关键表的行数，便于确认「数据确实搬过去了」
Write-Host ""
Write-Host "产物自检（脚本内应包含以下表的 INSERT）："
foreach ($t in @("knowledge_chunk", "knowledge_category", "sys_user", "conversation", "message", "guard_event")) {
    $hit = Select-String -Path $outFile -Pattern ("INSERT INTO ``{0}``" -f $t) -SimpleMatch -Quiet
    $mark = if ($hit) { "有数据" } else { "无（表为空或不存在，属正常）" }
    Write-Host ("  {0,-20} {1}" -f $t, $mark)
}

Write-Host ""
Write-Host "下一步（全栈一键起）：" -ForegroundColor Cyan
Write-Host "  pwsh tools\docker\up-full.ps1          # 含 .env 预检"
Write-Host "  或 docker compose --profile full up -d --build"
