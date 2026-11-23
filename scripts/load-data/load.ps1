<#
Нагрузочные данные микрозаймов (Windows PowerShell 5.1+ / PowerShell 7).
  .\scripts\load-data\load.ps1                       # 10 000 пользователей × 50 заявок = 500 000
  .\scripts\load-data\load.ps1 -Users 2000 -PerUser 50
  .\scripts\load-data\load.ps1 -Cleanup              # удалить их из Postgres и Mongo
Запускать при поднятом docker compose. Если PowerShell не даёт запускать скрипты:
  powershell -ExecutionPolicy Bypass -File .\scripts\load-data\load.ps1
#>
param(
    [int]$Users = 10000,
    [int]$PerUser = 50,
    [switch]$Cleanup
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path (Split-Path $PSScriptRoot))   # корень репозитория

function Invoke-Native([scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "команда завершилась с кодом $LASTEXITCODE" }
}

# SQL передаётся через stdin: в запросах только ASCII, кодировка консоли не важна.
function Invoke-Pg([string]$Sql) {
    $out = $Sql | docker compose exec -T postgres sh -c 'psql -X -At -v ON_ERROR_STOP=1 -U $POSTGRES_USER -d $POSTGRES_DB'
    if ($LASTEXITCODE -ne 0) { throw "psql завершился с кодом $LASTEXITCODE" }
    return $out
}

# Файлы копируются в контейнер (docker compose cp), а не передаются через конвейер:
# так кириллица в load.sql не портится кодировкой PowerShell.
if ($Cleanup) {
    $max = [int](Invoke-Pg "SELECT coalesce(max(substring(email from 5 for 6)::int), 0) FROM users WHERE email LIKE '%@load.local';")
    Write-Host "Postgres: удаляю нагрузочных пользователей ($max) и их заявки..."
    Invoke-Native { docker compose cp (Join-Path $PSScriptRoot 'cleanup.sql') postgres:/tmp/load-cleanup.sql }
    Invoke-Native { docker compose exec -T postgres sh -c 'psql -X -v ON_ERROR_STOP=1 -U $POSTGRES_USER -d $POSTGRES_DB -f /tmp/load-cleanup.sql' }
    Write-Host 'Mongo: удаляю их кредитную историю...'
    Invoke-Native { docker compose cp (Join-Path $PSScriptRoot 'cleanup-mongo.js') mongo:/tmp/load-cleanup.js }
    Invoke-Native { docker compose exec -T -e "LOAD_USERS=$max" mongo mongosh --quiet nosurprise /tmp/load-cleanup.js }
    return
}

$expected = $Users * $PerUser
Write-Host "Postgres: $Users пользователей x $PerUser заявок = $expected..."
Invoke-Native { docker compose cp (Join-Path $PSScriptRoot 'load.sql') postgres:/tmp/load.sql }
$psql = 'psql -X -v ON_ERROR_STOP=1 -v users={0} -v per_user={1} -U $POSTGRES_USER -d $POSTGRES_DB -f /tmp/load.sql' -f $Users, $PerUser
Invoke-Native { docker compose exec -T postgres sh -c $psql }

Write-Host 'Mongo: события идут через outbox -> RabbitMQ -> consumer. Ctrl+C прерывает только ожидание, не загрузку.'
$progress = @"
SELECT (SELECT count(*) FROM loan_applications a JOIN users u ON u.id = a.user_id
         WHERE u.email LIKE '%@load.local' AND a.credit_history_synced_event_id IS NOT NULL)
       || ' ' ||
       (SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND event_type = 'loan.history.changed');
"@
$watch = [Diagnostics.Stopwatch]::StartNew()
while ($true) {
    $parts = (Invoke-Pg $progress).Trim().Split(' ')
    $synced = [long]$parts[0]; $pending = [long]$parts[1]
    $seconds = [math]::Max(1, [int]$watch.Elapsed.TotalSeconds)
    $rate = [int]($synced / $seconds)
    Write-Progress -Activity 'Кредитная история в Mongo' -Status "$synced / $expected  (в outbox: $pending, $rate/с)" `
        -PercentComplete ([math]::Min(100, [int](100 * $synced / [math]::Max(1, $expected))))
    if ($synced -ge $expected) { break }
    Start-Sleep -Seconds 10
}
Write-Progress -Activity 'Кредитная история в Mongo' -Completed
Write-Host "Готово за $([int]$watch.Elapsed.TotalMinutes) мин."
Write-Host -NoNewline 'Документов в credit_history: '
Invoke-Native { docker compose exec -T mongo mongosh --quiet nosurprise --eval 'db.credit_history.countDocuments()' }
