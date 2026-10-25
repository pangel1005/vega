# Abre el emulador de coche de Google (Desktop Head Unit) para probar Vega en Android Auto desde el PC.
#
#   powershell -ExecutionPolicy Bypass -File tools\dhu.ps1
#
# Antes (una sola vez, ver docs\coche.md): tener Android Auto instalado en el teléfono, activar el modo
# desarrollador y «Iniciar servidor de unidad principal» desde el menú de Android Auto.
$ErrorActionPreference = 'Stop'
$dhu = "$env:USERPROFILE\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
if (-not (Test-Path $dhu)) { throw "Falta el DHU. Instálalo con: sdkmanager `"extras;google;auto`"" }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = "$env:USERPROFILE\Android\Sdk\platform-tools\adb.exe" }
& $adb forward tcp:5277 tcp:5277
if ($LASTEXITCODE) { throw 'No hay teléfono por USB (o no está autorizado).' }
Write-Host 'Abriendo el emulador de coche... (cierra su ventana para terminar)' -ForegroundColor Green
& $dhu
