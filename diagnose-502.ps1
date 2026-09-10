#!/usr/bin/env pwsh

Write-Host "Checking docker-compose status..." -ForegroundColor Cyan
docker-compose ps

Write-Host ""
Write-Host "Checking app1 logs for errors..." -ForegroundColor Yellow
docker logs rate-limiter-app1 2>&1 | tail -50

Write-Host ""
Write-Host "Checking Nginx logs..." -ForegroundColor Yellow
docker logs rate-limiter-nginx 2>&1 | tail -30