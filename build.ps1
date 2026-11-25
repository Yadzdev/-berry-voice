$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host 'Maven 3.9+ não foi encontrado no PATH.' -ForegroundColor Yellow
    Write-Host 'Instale Apache Maven 3.9+ e JDK 21+, abra um novo PowerShell e execute novamente:'
    Write-Host '  .\build.ps1'
    exit 1
}

$java = java -version 2>&1 | Out-String
if ($java -notmatch 'version "(21|2[2-9]|[3-9][0-9])') {
    Write-Host 'Este projeto requer JDK 21 ou superior.' -ForegroundColor Red
    exit 1
}

mvn --batch-mode --no-transfer-progress clean package

Write-Host "`nBuild Maven concluído. JARs gerados:" -ForegroundColor Green
Write-Host (Join-Path $PSScriptRoot 'velocity-plugin\target\berryvoice-velocity-2.1.1.jar')
Write-Host (Join-Path $PSScriptRoot 'paper-plugin\target\berryvoice-paper-2.1.1.jar')
