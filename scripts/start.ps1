# Arranca el asistente con un solo comando.
#
#   powershell -ExecutionPolicy Bypass -File scripts\start.ps1           (ventana con los registros)
#   powershell -ExecutionPolicy Bypass -File scripts\start.ps1 -Hidden   (en segundo plano)
#
# Hace, en orden:
#   1. Arranca Ollama si no está en marcha.
#   2. Arranca el servidor. El servidor ya vigila el cable USB: al conectar el
#      teléfono, lo enlaza y abre la app solo.

param([switch]$Hidden)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# En segundo plano (p. ej. al arrancar Windows) no hay ventana: todo queda en logs\arranque.log.
# Antes, si algo fallaba al encender el PC, se cerraba sin dejar rastro y Vega no volvía.
if ($Hidden) {
    New-Item -ItemType Directory -Force "$root\logs" | Out-Null
    Start-Transcript -Path "$root\logs\arranque.log" -Append | Out-Null
}

function Say($text, $color = 'Gray') { Write-Host $text -ForegroundColor $color }

# El PATH puede no estar al día si el usuario acaba de instalar algo.
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [Environment]::GetEnvironmentVariable('Path', 'Machine')
if (-not $env:UV_PYTHON_INSTALL_DIR) { $env:UV_PYTHON_INSTALL_DIR = "$env:USERPROFILE\.uv\python" }
if (-not $env:UV_CACHE_DIR) { $env:UV_CACHE_DIR = "$env:USERPROFILE\.uv\cache" }

# --- ¿Ya está en marcha? ---
if (Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue) {
    Say 'El asistente ya está en marcha (puerto 8000). Para pararlo: scripts\stop.ps1' 'Yellow'
    exit 0
}

# --- 1. Ollama ---
function Test-Ollama {
    try { Invoke-RestMethod 'http://127.0.0.1:11434/api/version' -TimeoutSec 2 | Out-Null; return $true } catch { return $false }
}
if (-not (Test-Ollama)) {
    $ollama = "$env:LOCALAPPDATA\Programs\Ollama\ollama app.exe"
    Say 'Arrancando Ollama...'
    if (Test-Path $ollama) {
        Start-Process -FilePath $ollama -WindowStyle Hidden
    } elseif (Get-Command ollama -ErrorAction SilentlyContinue) {
        Start-Process -FilePath 'ollama' -ArgumentList 'serve' -WindowStyle Hidden
    } else {
        throw 'No encuentro Ollama. Instálalo con scripts\instalar.ps1 (o winget install Ollama.Ollama).'
    }
    # Al encender el PC el disco va cargado y Ollama puede tardar más de un minuto.
    $ok = $false
    for ($i = 0; $i -lt 120 -and -not $ok; $i++) { Start-Sleep 1; $ok = Test-Ollama }
    if ($ok) { Say 'Ollama listo.' 'Green' }
    else { Say 'Aviso: Ollama aún no responde; arranco Vega igual (contestará cuando Ollama esté listo).' 'Yellow' }
} else {
    Say 'Ollama listo.' 'Green'
}

# --- 2. Servidor ---
if (-not (Get-Command uv -ErrorAction SilentlyContinue)) { throw 'No encuentro uv. Instálalo (README.md).' }
if ($Hidden) {
    Start-Process -FilePath uv -ArgumentList 'run', 'python', '-m', 'server' -WorkingDirectory $root -WindowStyle Hidden
    $up = $false
    for ($i = 0; $i -lt 90 -and -not $up; $i++) {
        Start-Sleep 1
        try { Invoke-RestMethod 'http://127.0.0.1:8000/health' -TimeoutSec 2 | Out-Null; $up = $true } catch { }
    }
    if ($up) { Say 'Asistente en marcha en segundo plano. Registros: logs\server.log  ·  Parar: scripts\stop.ps1' 'Green' }
    else { Say 'El servidor no respondió en 90 s: mira logs\server.log.' 'Red' }
    Stop-Transcript | Out-Null
} else {
    $Host.UI.RawUI.WindowTitle = 'Asistente (cierra esta ventana para pararlo)'
    Say 'Arrancando el servidor. Conecta el teléfono por USB: se enlazará solo.' 'Green'
    Say 'Para parar: cierra esta ventana o pulsa Ctrl+C.'
    uv run python -m server
}
