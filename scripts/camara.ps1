# Convierte la cámara del Redmi en una cámara de Windows («OBS Virtual Camera»).
#
#   powershell -ExecutionPolicy Bypass -File scripts\camara.ps1                 (enciende la cámara de la configuración)
#   powershell -ExecutionPolicy Bypass -File scripts\camara.ps1 -Facing back    (trasera)
#   powershell -ExecutionPolicy Bypass -File scripts\camara.ps1 -Stop           (apaga todo)
#
# Es lo mismo que el icono de cámara de la app: scrcpy (por el cable USB, sin instalar nada en el teléfono) y OBS
# (en la bandeja) trabajan en segundo plano; el vídeo no sale del PC. Ajustes en config/assistant.toml, [camera].

param(
    [ValidateSet('back', 'front')][string]$Facing,
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not $env:UV_PYTHON_INSTALL_DIR) { $env:UV_PYTHON_INSTALL_DIR = "$env:USERPROFILE\.uv\python" }
if (-not $env:UV_CACHE_DIR) { $env:UV_CACHE_DIR = "$env:USERPROFILE\.uv\cache" }

if ($Stop) { & uv run --no-sync python -m server.device.camera off }
elseif ($Facing) { & uv run --no-sync python -m server.device.camera on --facing $Facing }
else { & uv run --no-sync python -m server.device.camera on }
