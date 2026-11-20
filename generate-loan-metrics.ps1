param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$MailpitUrl = "http://localhost:8025",
    [int]$Count = 5,
    [string]$SubmitterEmail = "",
    [string]$SubmitterPassword = "Strong-pass-123",
    [string]$AdminEmail = "",
    [string]$AdminPassword = "Strong-pass-123"
)

$ErrorActionPreference = "Continue"

if ([string]::IsNullOrWhiteSpace($SubmitterEmail)) {
    $SubmitterEmail = "submitter-$(Get-Date -Format yyyyMMddHHmmss)@example.com"
}
if ([string]::IsNullOrWhiteSpace($AdminEmail)) {
    $AdminEmail = "admin-$(Get-Date -Format yyyyMMddHHmmss)@example.com"
}

function Invoke-Api {
    param([string]$Method,[string]$Url,[hashtable]$Headers=@{},$Body=$null)
    try {
        $params = @{ Uri=$Url; Method=$Method; Headers=$Headers; UseBasicParsing=$true }
        if ($Body -ne $null) { $params.Body = ($Body | ConvertTo-Json -Depth 10); $params.ContentType = "application/json" }
        return Invoke-RestMethod @params
    } catch {
        if ($_.ErrorDetails) { Write-Host "API ERROR: $($_.ErrorDetails.Message)" -ForegroundColor Red }
        else { Write-Host "API ERROR: $($_.Exception.Message)" -ForegroundColor Red }
        return $null
    }
}

function Register-And-Verify {
    param([string]$Email,[string]$Password)
    $regBody = @{ email = $Email; password = $Password }
    Invoke-Api -Method POST -Url "$BaseUrl/api/auth/register" -Body $regBody | Out-Null
    # grant admin role later if needed; here just register
    Start-Sleep -Seconds 2
    $token = $null
    for ($i = 0; $i -lt 20 -and -not $token; $i++) {
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
    if (-not $token) { Write-Host "Verification failed for $Email" -ForegroundColor Red; exit 1 }
    return $token
}

# Create admin user
Write-Host "Creating admin user $AdminEmail ..."
$adminToken = Register-And-Verify -Email $AdminEmail -Password $AdminPassword
# grant admin role
docker exec nosurprise-postgres-1 psql -U nosurprise -d nosurprise -c "INSERT INTO user_roles (user_id, role) SELECT id, 'ADMIN' FROM users WHERE email='$AdminEmail' ON CONFLICT DO NOTHING;" | Out-Null
# re-login to get token with ADMIN role
$adminLogin = @{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json
$adminToken = (Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -Body $adminLogin -ContentType "application/json").accessToken
Write-Host "Admin ready."

$pngPath = "$env:TEMP\loadtest.png"
[IO.File]::WriteAllBytes($pngPath, [byte[]]@(0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,1,2,3,4))

# Get loan type using admin token (loan types endpoint requires auth)
$types = Invoke-Api -Method GET -Url "$BaseUrl/api/loan-types" -Headers @{ Authorization = "Bearer $adminToken" }
$loanTypeId = $types[0].id
Write-Host "Using loan type $loanTypeId"

$rand = New-Object System.Random
for ($i = 1; $i -le $Count; $i++) {
    if ($i -gt 1) { Start-Sleep -Seconds 15 }
    # Create a separate submitter per loan to avoid active-application conflicts
    $submitterEmail = "submitter-$i-$(Get-Date -Format HHmmss)@example.com"
    $submitterToken = Register-And-Verify -Email $submitterEmail -Password $SubmitterPassword
    & curl.exe -s -o NUL -X POST "$BaseUrl/api/me/kyc/passport" -H "Authorization: Bearer $submitterToken" -F "file=@$pngPath" | Out-Null

    $body = @{
        loanTypeId = $loanTypeId
        amount = 1000
        termDays = 14
        profile = @{
            lastName = "Load"
            firstName = "Test$i"
            middleName = $null
            phone = "+375291234567"
            monthlyIncome = 2000
        }
    }
    $app = Invoke-Api -Method POST -Url "$BaseUrl/api/loans/applications" -Headers @{ Authorization = "Bearer $submitterToken" } -Body $body
    if (-not $app) { continue }
    $id = $app.id
    $version = $app.version
    $scenario = $rand.Next(0, 4)
    switch ($scenario) {
        0 {
            Invoke-Api -Method POST -Url "$BaseUrl/api/loans/applications/$id/cancel" -Headers @{ Authorization = "Bearer $submitterToken" } | Out-Null
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
            Write-Host "Loan $i ($id): SUBMITTED -> APPROVED (not completed)"
        }
    }
}

Write-Host ""
Write-Host "Created $Count loan transitions."

# Report
$report = Invoke-Api -Method GET -Url "$BaseUrl/api/admin/loan-state-metrics/report" -Headers @{ Authorization = "Bearer $adminToken" }
if ($report) {
    Write-Host "Report: totalTransitions=$($report.totalTransitions), totalAmount=$($report.totalAmount)"
}

Write-Host "Done."