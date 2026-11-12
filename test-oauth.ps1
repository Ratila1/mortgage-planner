$Base = "http://localhost:8080"

Write-Host "=== 1. Backend health ==="
try {
    $health = Invoke-RestMethod -Uri "$Base/actuator/health" -Method Get
    Write-Host "Backend health: $($health.status)"
} catch {
    Write-Host "Backend unavailable: $($_.Exception.Message)"
    exit 1
}

Write-Host ""
Write-Host "=== 2. OAuth endpoints ==="

$GoogleUrl = "$Base/oauth2/authorization/google"
$GithubUrl = "$Base/oauth2/authorization/github"

Write-Host "Google authorize: $GoogleUrl"
Write-Host "GitHub authorize: $GithubUrl"

try {
    $g = Invoke-WebRequest -Uri $GoogleUrl -Method Get -MaximumRedirection 0 -ErrorAction SilentlyContinue
    Write-Host "Google redirect status: $($g.StatusCode)"
} catch {
    $resp = $_.Exception.Response
    if ($resp) {
        Write-Host "Google redirect status: $([int]$resp.StatusCode)"
    } else {
        Write-Host "Google endpoint error: $($_.Exception.Message)"
    }
}

try {
    $gh = Invoke-WebRequest -Uri $GithubUrl -Method Get -MaximumRedirection 0 -ErrorAction SilentlyContinue
    Write-Host "GitHub redirect status: $($gh.StatusCode)"
} catch {
    $resp = $_.Exception.Response
    if ($resp) {
        Write-Host "GitHub redirect status: $([int]$resp.StatusCode)"
    } else {
        Write-Host "GitHub endpoint error: $($_.Exception.Message)"
    }
}

Write-Host ""
Write-Host "=== 3. Opening OAuth in browser ==="
Start-Process $GoogleUrl
Start-Sleep -Seconds 2
Start-Process $GithubUrl

Write-Host ""
Write-Host "Now in browser:"
Write-Host "1) Login with Google"
Write-Host "2) Login with GitHub"
Write-Host "3) If you are redirected back or get JWT, OAuth works"