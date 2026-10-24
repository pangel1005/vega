# Baja la librería sherpa-onnx para la app Android (Whisper y Piper en el teléfono).
#
#   powershell -ExecutionPolicy Bypass -File tools\android_sherpa.ps1
#
# Va a android\app\libs (fuera de git: pesa 50 MB).
$ErrorActionPreference = 'Stop'
$version = '1.13.8'
$dest = Join-Path (Split-Path -Parent $PSScriptRoot) "android\app\libs\sherpa-onnx-$version.aar"
if (Test-Path $dest) { Write-Host "Ya está: $dest"; exit 0 }
New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"
Write-Host "Descargando $url ..."
Invoke-WebRequest $url -OutFile $dest
Write-Host "Listo: $dest" -ForegroundColor Green
