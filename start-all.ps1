# GhostKey Stealth Suite Launcher
Write-Host "=======================================================" -ForegroundColor Green
Write-Host "         [GHOSTKEY STEALTH MESSAGING SUITE]           " -ForegroundColor Cyan
Write-Host "  Zero-Trace OpenPGP Blind Dead-Drop with Tor Routing  " -ForegroundColor White
Write-Host "=======================================================" -ForegroundColor Green
Write-Host ""

$baseDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "Starting GhostKey Blind Relay on port 8765..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit", "-Command", "Set-Location '$baseDir\server'; npm start"

Start-Sleep -Seconds 2

Write-Host "Opening Stealth Web Console in browser..." -ForegroundColor Green
Start-Process "http://localhost:8765/"

Write-Host ""
Write-Host "=======================================================" -ForegroundColor Green
Write-Host "GhostKey System is now active!" -ForegroundColor Green
Write-Host "Relay:       http://127.0.0.1:8765" -ForegroundColor Cyan
Write-Host "Web Console: http://localhost:8765/" -ForegroundColor Cyan
Write-Host "=======================================================" -ForegroundColor Green
