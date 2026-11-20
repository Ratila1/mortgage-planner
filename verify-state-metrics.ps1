param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$MetricsUser = "prometheus",
    [string]$MetricsPassword = "",
    [string]$ReportDir = "$env:TEMP\nosurprise-reports",
    [switch]$RunTests,
    [switch]$FailOnMissing
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($MetricsPassword)) {
    try {
        $MetricsPassword = (docker exec nosurprise-backend-1 sh -c "printenv METRICS_PASSWORD" 2>$null).Trim()
    } catch { $MetricsPassword = "" }
    if ([string]::IsNullOrWhiteSpace($MetricsPassword)) { $MetricsPassword = "prometheus" }
}

function Write-Section([string]$Title) {
    Write-Host ""
    Write-Host "=== $Title ===" -ForegroundColor Cyan
}

function Test-Endpoint([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
        return $response
    } catch {
        return $null
    }
}

Write-Section "Git state"
$branch = git rev-parse --abbrev-ref HEAD
Write-Host "Current branch: $branch"
if ($branch -ne "feature/loan-state-metrics-logging") {
    Write-Host "WARNING: expected feature/loan-state-metrics-logging" -ForegroundColor Yellow
}
$status = git status --porcelain
if ($status) {
    Write-Host "Working tree has changes:" -ForegroundColor Yellow
    $status | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "Working tree clean."
}

if ($RunTests) {
    Write-Section "Running backend tests"
    Push-Location (Join-Path $PSScriptRoot "backend")
    try {
        & .\gradlew.bat test --tests "dev.nosurprise.loan.application.state.*" --tests "dev.nosurprise.loan.jobs.*" --offline
        if ($LASTEXITCODE -ne 0) { throw "Gradle tests failed with code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

Write-Section "Metrics endpoint"
$promUrl = "$BaseUrl/actuator/prometheus"
$metricsResponse = $null
try {
    $pair = "$MetricsUser`:$MetricsPassword"
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($pair)
    $basic = [Convert]::ToBase64String($bytes)
    $metricsResponse = Invoke-WebRequest -Uri $promUrl -Headers @{ Authorization = "Basic $basic" } -UseBasicParsing -TimeoutSec 10
} catch {
    Write-Host "Could not fetch $promUrl : $($_.Exception.Message)" -ForegroundColor Yellow
}

# Fetch metrics via docker exec (backend port is not published on host)
$metricsBody = $null
try {
    $auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$MetricsUser`:$MetricsPassword"))
    $perlScript = 'use IO::Socket; my $s = IO::Socket::INET->new(PeerAddr=>"127.0.0.1:8080", Proto=>"tcp", Timeout=>5) or die $!; print $s "GET /actuator/prometheus HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic ' + $auth + '\r\nConnection: close\r\n\r\n"; my $r=""; while(<$s>){ $r.=$_; } close $s; print $r;'
    $metricsBody = $perlScript | docker exec -i nosurprise-backend-1 perl 2>$null
} catch {
    Write-Host "Could not fetch metrics via docker: $($_.Exception.Message)" -ForegroundColor Yellow
}

if ($metricsBody) {
    $metricNames = @(
        "nosurprise_loan_state_transitions_total",
        "nosurprise_loan_state_enter_total",
        "nosurprise_loan_state_exit_total",
        "nosurprise_loan_state_transition_amount_total"
    )
    foreach ($name in $metricNames) {
        if ($metricsBody -match [regex]::Escape($name)) {
            Write-Host "FOUND: $name" -ForegroundColor Green
        } else {
            Write-Host "MISSING: $name" -ForegroundColor Yellow
            if ($FailOnMissing) { throw "Metric $name not found" }
        }
    }
} else {
    Write-Host "Backend metrics not reachable." -ForegroundColor Yellow
}

Write-Section "Daily report files"
if (Test-Path $ReportDir) {
    $reports = Get-ChildItem -Path $ReportDir -Filter "loan-state-metrics-*.json" -File -ErrorAction SilentlyContinue
    if ($reports) {
        Write-Host "Found $($reports.Count) report(s):" -ForegroundColor Green
        $reports | Sort-Object LastWriteTime -Descending | Select-Object -First 10 | ForEach-Object {
            Write-Host "  $($_.FullName) ($($_.Length) bytes)"
        }
    } else {
        Write-Host "No report files found in $ReportDir" -ForegroundColor Yellow
    }
} else {
    Write-Host "Report directory does not exist: $ReportDir" -ForegroundColor Yellow
}

Write-Section "Open known issues (from CLAUDE.md)"
$issues = @(
    "Email verification link points to /api/auth/verify-email instead of frontend /verify-email?token=",
    "OAuth2 success handler returns JSON instead of redirect to /",
    "Missing GET /api/admin/offers (admin sees only active offers)",
    "GlobalExceptionHandler returns 500 on unknown path instead of 404"
)
$issues | ForEach-Object { Write-Host "  - $_" }

Write-Section "Done"
Write-Host "Verification finished." -ForegroundColor Green