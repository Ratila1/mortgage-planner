param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$MailpitUrl = "http://localhost:8025",
    [int]$Count = 12,
    [string]$SubmitterPassword = "Strong-pass-123",
    [string]$AdminEmail = "",
    [string]$AdminPassword = "Strong-pass-123"
)

$ErrorActionPreference = "Continue"

if ([string]::IsNullOrWhiteSpace($AdminEmail)) {
    $AdminEmail = "admin-$(Get-Date -Format yyyyMMddHHmmss)@example.com"
}

$pngPath = "$env:TEMP\loadtest.png"
if (-not (Test-Path $pngPath)) {
    [IO.File]::WriteAllBytes($pngPath, [byte[]]@(0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,1,2,3,4))
}

function Invoke-Api {
    param([string]$Method,[string]$Url,[hashtable]$Headers=@{},$Body=$null)
    try {
        $params = @{ Uri=$Url; Method=$Method; Headers=$Headers; UseBasicParsing=$true; TimeoutSec=30 }
        if ($Body -ne $null) {
            $json = $Body | ConvertTo-Json -Depth 10 -Compress
            $params.Body = [Text.Encoding]::UTF8.GetBytes($json)
            $params.ContentType = "application/json; charset=utf-8"
        }
        return Invoke-RestMethod @params
    } catch {
        $msg = $_.Exception.Message
        try {
            $resp = $_.Exception.Response
            if ($resp) {
                $stream = $resp.GetResponseStream()
                if ($stream) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $body = $reader.ReadToEnd()
                    $reader.Close()
                    if ($body) { $msg = $body }
                }
            }
        } catch {}
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $msg = $_.ErrorDetails.Message }
        Write-Host "API ERROR $Method $Url : $msg" -ForegroundColor Red
        return $null
    }
}

function Get-RedisValue([string]$Key) {
    try {
        $out = docker exec nosurprise-redis-1 redis-cli GET $Key 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        $val = ($out | Out-String).Trim()
        if ($val -eq "" -or $val -eq "(nil)") { return $null }
        return $val
    } catch { return $null }
}

function Remove-RedisKey([string]$Key) {
    try { docker exec nosurprise-redis-1 redis-cli DEL $Key 2>$null | Out-Null } catch {}
}

function Wait-SmsSuccess {
    param([string]$SmsId,[int]$TimeoutSec = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $status = (docker exec nosurprise-postgres-1 psql -U nosurprise -d nosurprise -t -A -c "select status from sms.sms_request where id = '$SmsId';" 2>$null | Out-String).Trim()
        if ($status -eq "SUCCESS") { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Get-JwtSubject([string]$Token) {
    try {
        $parts = $Token.Split('.')
        if ($parts.Length -lt 2) { return $null }
        $b64 = $parts[1].Replace('-','+').Replace('_','/')
        switch ($b64.Length % 4) { 0 {} 2 { $b64 += "==" } 3 { $b64 += "=" } }
        $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64))
        return ($json | ConvertFrom-Json).sub
    } catch { return $null }
}

function Register-And-Verify {
    param([string]$Email,[string]$Password)
    Invoke-Api -Method POST -Url "$BaseUrl/api/auth/register" -Body @{ email = $Email; password = $Password } | Out-Null
    Start-Sleep -Seconds 2
    $token = $null
    for ($i = 0; $i -lt 30 -and -not $token; $i++) {
        $msgs = Invoke-RestMethod -Uri "$MailpitUrl/api/v1/messages" -TimeoutSec 5
        $msg = $msgs.messages | Where-Object { $_.To[0].Address -eq $Email } | Sort-Object Created -Descending | Select-Object -First 1
        if ($msg) {
            $detail = Invoke-RestMethod -Uri "$MailpitUrl/api/v1/message/$($msg.ID)"
            $text = if ($detail.Text) { $detail.Text } else { $detail.HTML }
            $m = [regex]::Match($text, "token=([A-Za-z0-9_-]+)")
            if ($m.Success) {
                $vr = Invoke-Api -Method POST -Url "$BaseUrl/api/auth/verify-email" -Body @{ token = $m.Groups[1].Value }
                if ($vr) { $token = $vr.accessToken }
            }
        }
        if (-not $token) { Start-Sleep -Seconds 1 }
    }
    if (-not $token) { throw "Verification failed for $Email" }
    return $token
}

function New-ReadyUser {
    param([int]$Index)
    $email = "submitter-$Index-$(Get-Date -Format HHmmss)@example.com"
    $password = $SubmitterPassword
    $token = Register-And-Verify -Email $email -Password $password
    $userId = Get-JwtSubject -Token $token
    $phone = "+37529" + (Get-Random -Minimum 1000000 -Maximum 9999999).ToString()
    $profileBody = @{ lastName = "Иванов"; firstName = "Тест$Index"; middleName = "Иванович"; phone = $phone; monthlyIncome = 2000 }
    $r = Invoke-Api -Method PUT -Url "$BaseUrl/api/me/profile" -Headers @{ Authorization = "Bearer $token" } -Body $profileBody
    if (-not $r) { throw "profile update failed for $email" }

    # phone verification via SMS (code from shared Redis)
    Invoke-Api -Method POST -Url "$BaseUrl/api/me/phone/verification" -Headers @{ Authorization = "Bearer $token" } | Out-Null
    $smsId = $null
    for ($i = 0; $i -lt 15 -and -not $smsId; $i++) { $smsId = Get-RedisValue "phone-sms-id:$userId"; if (-not $smsId) { Start-Sleep -Seconds 1 } }
    if (-not $smsId) { throw "no phone sms id for $email" }
    $code = $null
    for ($i = 0; $i -lt 15 -and -not $code; $i++) { $code = Get-RedisValue "sms:otp:code:$smsId"; if (-not $code) { Start-Sleep -Seconds 1 } }
    if (-not $code) { throw "no phone sms code for $email" }
    if (-not (Wait-SmsSuccess -SmsId $smsId -TimeoutSec 20)) { throw "sms not dispatched for $email" }
    $vr = Invoke-Api -Method POST -Url "$BaseUrl/api/me/phone/verification/confirm" -Headers @{ Authorization = "Bearer $token" } -Body @{ code = $code }
    # 204 returns null; treat as success if no error printed? We'll assume success.

    # passport
    & curl.exe -s -o NUL -X POST "$BaseUrl/api/me/kyc/passport" -H "Authorization: Bearer $token" -F "file=@$pngPath" | Out-Null

    return @{ Email = $email; Password = $password; Token = $token; UserId = $userId; Phone = $phone }
}

function Wait-Contract {
    param([hashtable]$User,[string]$AppId,[int]$TimeoutSec = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $app = Invoke-Api -Method GET -Url "$BaseUrl/api/loans/applications/$AppId" -Headers @{ Authorization = "Bearer $($User.Token)" }
        if ($app -and $app.documents -and $app.documents.Count -ge 1) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Sign-Application {
    param([hashtable]$User,[string]$AppId)
    Remove-RedisKey "sign-otp:cooldown:$($User.UserId)"
    $req = Invoke-Api -Method POST -Url "$BaseUrl/api/loans/applications/$AppId/signature" -Headers @{ Authorization = "Bearer $($User.Token)" }
    if (-not $req) { throw "request signature failed for $AppId" }

    $smsId = $null
    for ($i = 0; $i -lt 15 -and -not $smsId; $i++) { $smsId = Get-RedisValue "sign-sms-id:$AppId"; if (-not $smsId) { Start-Sleep -Seconds 1 } }
    if (-not $smsId) { throw "no sign sms id for $AppId" }
    $code = $null
    for ($i = 0; $i -lt 15 -and -not $code; $i++) { $code = Get-RedisValue "sms:otp:code:$smsId"; if (-not $code) { Start-Sleep -Seconds 1 } }
    if (-not $code) { throw "no sign sms code for $AppId" }
    if (-not (Wait-SmsSuccess -SmsId $smsId -TimeoutSec 20)) { throw "sign sms not dispatched for $AppId" }

    $confirm = Invoke-Api -Method POST -Url "$BaseUrl/api/loans/applications/$AppId/signature/confirm" -Headers @{ Authorization = "Bearer $($User.Token)" } -Body @{ code = $code }
    if (-not $confirm) { throw "confirm signature failed for $AppId" }
    return $confirm
}

# --- admin setup ---
Write-Host "Creating admin user $AdminEmail ..."
$adminToken = Register-And-Verify -Email $AdminEmail -Password $AdminPassword
# grant admin role
docker exec nosurprise-postgres-1 psql -U nosurprise -d nosurprise -c "INSERT INTO user_roles (user_id, role) SELECT id, 'ADMIN' FROM users WHERE email='$AdminEmail' ON CONFLICT DO NOTHING;" | Out-Null
# re-login to get token with ADMIN role
$adminLoginBody = @{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json
$adminToken = (Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -Body $adminLoginBody -ContentType "application/json").accessToken
Write-Host "Admin ready."

# loan type
$types = Invoke-Api -Method GET -Url "$BaseUrl/api/loan-types" -Headers @{ Authorization = "Bearer $adminToken" }
$loanTypeId = $types[0].id
Write-Host "Using loan type $loanTypeId"

$expiredCandidates = @()

for ($i = 1; $i -le $Count; $i++) {
    if ($i -gt 1) { Start-Sleep -Seconds 15 }
    Write-Host ""
    Write-Host "=== Loan $i / $Count ===" -ForegroundColor Cyan
    try {
        $user = New-ReadyUser -Index $i
    } catch {
        Write-Host "Failed to prepare user for loan $i : $($_.Exception.Message)" -ForegroundColor Red
        continue
    }

    $body = @{
        loanTypeId = $loanTypeId
        amount = 1000
        termDays = 14
        profile = @{
            lastName = "Иванов"
            firstName = "Тест$i"
            middleName = "Иванович"
            phone = $user.Phone
            monthlyIncome = 2000
        }
    }
    $app = Invoke-Api -Method POST -Url "$BaseUrl/api/loans/applications" -Headers @{ Authorization = "Bearer $($user.Token)" } -Body $body
    if (-not $app) { Write-Host "submit failed for loan $i" -ForegroundColor Red; continue }
    $id = $app.id
    $version = $app.version

    $scenario = $i % 7
    switch ($scenario) {
        0 {
            Invoke-Api -Method POST -Url "$BaseUrl/api/loans/applications/$id/cancel" -Headers @{ Authorization = "Bearer $($user.Token)" } | Out-Null
            Write-Host "Loan $i ($id): SUBMITTED -> CANCELLED"
        }
        1 {
            $rejectBody = @{ reason = "test reject"; version = $version }
            Invoke-Api -Method POST -Url "$BaseUrl/api/admin/loan-applications/$id/reject" -Headers @{ Authorization = "Bearer $adminToken" } -Body $rejectBody | Out-Null
            Write-Host "Loan $i ($id): SUBMITTED -> REJECTED"
        }
        2 {
            $decisionBody = @{ comment = "ok"; version = $version }
            Invoke-Api -Method POST -Url "$BaseUrl/api/admin/loan-applications/$id/approve" -Headers @{ Authorization = "Bearer $adminToken" } -Body $decisionBody | Out-Null
            Write-Host "Loan $i ($id): SUBMITTED -> APPROVED"
        }
        3 {
            $decisionBody = @{ comment = "ok"; version = $version }
            Invoke-Api -Method POST -Url "$BaseUrl/api/admin/loan-applications/$id/approve" -Headers @{ Authorization = "Bearer $adminToken" } -Body $decisionBody | Out-Null
            # make offer expired and let OfferExpiryJob process
            docker exec nosurprise-postgres-1 psql -U nosurprise -d nosurprise -c "update loan_applications set offer_expires_at = now() - interval '1 minute' where id = '$id';" | Out-Null
            $expiredCandidates += $id
            Write-Host "Loan $i ($id): SUBMITTED -> APPROVED -> (pending EXPIRED)"
        }
        4 {
            $decisionBody = @{ comment = "ok"; version = $version }
            Invoke-Api -Method POST -Url "$BaseUrl/api/admin/loan-applications/$id/approve" -Headers @{ Authorization = "Bearer $adminToken" } -Body $decisionBody | Out-Null
            if (Wait-Contract -User $user -AppId $id -TimeoutSec 60) {
                try {
                    Sign-Application -User $user -AppId $id | Out-Null
                    Write-Host "Loan $i ($id): SUBMITTED -> APPROVED -> ACTIVE"
                } catch {
                    Write-Host "Signing failed for $id : $($_.Exception.Message)" -ForegroundColor Yellow
                }
            } else {
                Write-Host "Contract not ready for $id" -ForegroundColor Yellow
            }
        }
        5 {
            $decisionBody = @{ comment = "ok"; version = $version }
            Invoke-Api -Method POST -Url "$BaseUrl/api/admin/loan-applications/$id/approve" -Headers @{ Authorization = "Bearer $adminToken" } -Body $decisionBody | Out-Null
            if (Wait-Contract -User $user -AppId $id -TimeoutSec 60) {
                try {
                    Sign-Application -User $user -AppId $id | Out-Null
                    # mark repaid
                    $app2 = Invoke-Api -Method GET -Url "$BaseUrl/api/loans/applications/$id" -Headers @{ Authorization = "Bearer $($user.Token)" }
                    $v2 = $app2.version
                    $repaidBody = @{ comment = "repaid"; version = $v2 }
                    Invoke-Api -Method POST -Url "$BaseUrl/api/admin/loan-applications/$id/repaid" -Headers @{ Authorization = "Bearer $adminToken" } -Body $repaidBody | Out-Null
                    Write-Host "Loan $i ($id): SUBMITTED -> APPROVED -> ACTIVE -> REPAID"
                } catch {
                    Write-Host "Signing/repaid failed for $id : $($_.Exception.Message)" -ForegroundColor Yellow
                }
            } else {
                Write-Host "Contract not ready for $id" -ForegroundColor Yellow
            }
        }
        6 {
            $decisionBody = @{ comment = "ok"; version = $version }
            Invoke-Api -Method POST -Url "$BaseUrl/api/admin/loan-applications/$id/approve" -Headers @{ Authorization = "Bearer $adminToken" } -Body $decisionBody | Out-Null
            Write-Host "Loan $i ($id): SUBMITTED -> APPROVED (left approved)"
        }
    }
}

# wait for expired offers to be processed by OfferExpiryJob
if ($expiredCandidates.Count -gt 0) {
    Write-Host ""
    Write-Host "Waiting for OfferExpiryJob to process $($expiredCandidates.Count) expired offers (up to 70s)..."
    Start-Sleep -Seconds 70
}

Write-Host ""
Write-Host "Created $Count loan transitions."

# Report
$report = Invoke-Api -Method GET -Url "$BaseUrl/api/admin/loan-state-metrics/report" -Headers @{ Authorization = "Bearer $adminToken" }
if ($report) {
    Write-Host "Report: totalTransitions=$($report.totalTransitions), totalAmount=$($report.totalAmount)"
}

# Dump to file
$dump = Invoke-Api -Method POST -Url "$BaseUrl/api/admin/loan-state-metrics/dump" -Headers @{ Authorization = "Bearer $adminToken" }
if ($dump) {
    Write-Host "Dump file: $($dump.file)"
}

Write-Host "Done."
