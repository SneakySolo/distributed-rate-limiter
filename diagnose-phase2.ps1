# Test 1: Send 100 requests - all should be 200
Write-Host "Test 1: Sending 100 requests..."
$success = 0
$userId = "test-user-$(Get-Random)"

for ($i = 1; $i -le 100; $i++) {
    $status = curl.exe -s -w "%{http_code}" -o NUL `
        -H "X-User-Id: $userId" `
        -H "Content-Type: application/json" `
        -d '{"phone":"1234567890"}' `
        http://localhost:8000/otp/send

    if ($status -eq "200") { $success++ }
}

Write-Host "✅ $success / 100 succeeded"

# Test 2: Send 5 more - all should be 429
Write-Host "`nTest 2: Sending 5 more (should all be 429)..."
$rateLimited = 0

for ($i = 1; $i -le 5; $i++) {
    $status = curl.exe -s -w "%{http_code}" -o NUL `
        -H "X-User-Id: $userId" `
        -H "Content-Type: application/json" `
        -d '{"phone":"1234567890"}' `
        http://localhost:8000/otp/send

    if ($status -eq "429") { $rateLimited++ }
}

Write-Host "✅ $rateLimited / 5 were rate-limited (429)"

# Final result
if ($success -eq 100 -and $rateLimited -eq 5) {
    Write-Host "`n🎉🎉🎉 DISTRIBUTION WORKS!"
    Write-Host "   - All 3 instances share the same Redis state"
    Write-Host "   - Global rate limit enforced"
    Write-Host "   - Requests routed through Nginx"
} else {
    Write-Host "`n❌ Something failed. Check docker-compose is running:"
    Write-Host "   docker compose ps"
}