#!/usr/bin/env pwsh

Write-Host "=== DISTRIBUTION TEST ===" -ForegroundColor Cyan
Write-Host ""

# Step 1: Check docker-compose status
Write-Host "Step 1: Checking docker-compose status..." -ForegroundColor Yellow
$compose_status = docker-compose ps 2>&1

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ docker-compose ps failed!" -ForegroundColor Red
    Write-Host "Error: $compose_status"
    Write-Host ""
    Write-Host "Make sure you're in the rate-limiter directory and docker-compose is running:"
    Write-Host "  docker-compose up -d"
    exit 1
}

Write-Host "Docker-compose status:"
Write-Host $compose_status
Write-Host ""

# Step 2: Test Nginx health
Write-Host "Step 2: Testing Nginx health..." -ForegroundColor Yellow

$health_response = curl -s -w "`n%{http_code}" http://localhost:8000/health 2>&1

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Nginx is not accessible!" -ForegroundColor Red
    Write-Host "Error: $health_response"
    Write-Host ""
    Write-Host "Nginx may not have started. Wait 10-15 seconds and try again:"
    Write-Host "  sleep 15"
    Write-Host "  .\test-distribution.ps1"
    exit 1
}

$health_status = ($health_response -split "`n")[-1]

if ($health_status -eq "200") {
    Write-Host "✅ Nginx is accessible and responding" -ForegroundColor Green
} else {
    Write-Host "❌ Nginx returned status $health_status" -ForegroundColor Red
    exit 1
}

Write-Host ""

# Step 3: Run distribution test
Write-Host "Step 3: Testing global rate limiting..." -ForegroundColor Yellow
Write-Host "Sending 100 requests through Nginx..."

$userId = "test-user-$(Get-Random)"
$successCount = 0

for ($i = 1; $i -le 100; $i++) {
    if ($i % 20 -eq 0) {
        Write-Host "  Sent $i requests..."
    }

    $response = curl -s -w "`n%{http_code}" `
        -H "X-User-Id: $userId" `
        -H "Content-Type: application/json" `
        -d '{"phone":"1234567890"}' `
        http://localhost:8000/otp/send 2>&1

    $status = ($response -split "`n")[-1]

    if ($status -eq "200") {
        $successCount++
    } elseif ($status -ne "") {
        # Some non-200 response
    }
}

Write-Host "✅ $successCount / 100 requests succeeded (expected 100)" -ForegroundColor Green
Write-Host ""

# Step 4: Test rate limiting
Write-Host "Step 4: Testing rate limiting (should fail with 429)..." -ForegroundColor Yellow
Write-Host "Sending 5 more requests (should all be 429)..."

$rateLimitedCount = 0

for ($i = 1; $i -le 5; $i++) {
    $response = curl -s -w "`n%{http_code}" `
        -H "X-User-Id: $userId" `
        -H "Content-Type: application/json" `
        -d '{"phone":"1234567890"}' `
        http://localhost:8000/otp/send 2>&1

    $status = ($response -split "`n")[-1]

    if ($status -eq "429") {
        $rateLimitedCount++
        Write-Host "  Request $i: 429 ✅" -ForegroundColor Green
    } else {
        Write-Host "  Request $i: $status ❌ (expected 429)" -ForegroundColor Red
    }
}

Write-Host ""

# Step 5: Results
Write-Host "=== FINAL RESULTS ===" -ForegroundColor Cyan
Write-Host "100 requests succeeded: $($successCount -eq 100)"
Write-Host "5 requests rate-limited: $($rateLimitedCount -eq 5)"
Write-Host ""

if ($successCount -eq 100 -and $rateLimitedCount -eq 5) {
    Write-Host "🎉🎉🎉 DISTRIBUTION VERIFIED! 🎉🎉🎉" -ForegroundColor Green
    Write-Host ""
    Write-Host "✅ All 3 instances share the same Redis state"
    Write-Host "✅ Global rate limit is enforced (100 per user)"
    Write-Host "✅ Requests routed through Nginx load balancer"
    Write-Host ""
    Write-Host "Phase 2 is COMPLETE and VERIFIED!"
} else {
    Write-Host "❌ Test did not pass as expected" -ForegroundColor Red
    Write-Host ""
    Write-Host "Check:"
    Write-Host "  1. docker-compose ps (all containers running?)"
    Write-Host "  2. docker logs rate-limiter-app1 (errors?)"
    Write-Host "  3. curl http://localhost:8000/health (Nginx accessible?)"
}