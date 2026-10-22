# Arranca el asistente con un solo comando.
#
#   powershell -ExecutionPolicy Bypass -File scripts\start.ps1           (ventana con los registros)
#   powershell -ExecutionPolicy Bypass -File scripts\start.ps1 -Hidden   (en segundo plano)
#
# Hace, en orden:
#   1. Arranca Ollama si no está en marcha.
#   2. Compila la interfaz web si falta (ui\dist).
#   3. Arranca el servidor. El servidor ya vigila el cable USB: al conectar el
#      teléfono, lo enlaza y abre la app solo.

param([switch]$Hidden)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

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
    if (-not (Test-Path $ollama)) { throw "No encuentro Ollama en $ollama. Instálalo (README.md)." }
    Say 'Arrancando Ollama...'
    Start-Process -FilePath $ollama -WindowStyle Hidden
    $ok = $false
    for ($i = 0; $i -lt 30 -and -not $ok; $i++) { Start-Sleep 1; $ok = Test-Ollama }
    if (-not $ok) { throw 'Ollama no responde tras 30 s.' }
}
Say 'Ollama listo.' 'Green'

# --- 2. Interfaz web ---
if (-not (Test-Path "$root\ui\dist\index.html")) {
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
        Say 'Aviso: falta Node.js; la interfaz web del PC no estará disponible (la app del teléfono sí).' 'Yellow'
    } else {
        Say 'Compilando la interfaz web (solo la primera vez)...'
        Push-Location "$root\ui"
        try {
            npm ci --no-fund --no-audit | Out-Null
            npm run build | Out-Null
        } finally { Pop-Location }
    }
}

# --- 3. Servidor ---
if (-not (Get-Command uv -ErrorAction SilentlyContinue)) { throw 'No encuentro uv. Instálalo (README.md).' }
if ($Hidden) {
    Start-Process -FilePath uv -ArgumentList 'run', 'python', '-m', 'server' -WorkingDirectory $root -WindowStyle Hidden
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep 1
        try { Invoke-RestMethod 'http://127.0.0.1:8000/health' -TimeoutSec 2 | Out-Null; break } catch { }
    }
    Say 'Asistente en marcha en segundo plano. Registros: logs\server.log  ·  Parar: scripts\stop.ps1' 'Green'
} else {
    $Host.UI.RawUI.WindowTitle = 'Asistente (cierra esta ventana para pararlo)'
    Say 'Arrancando el servidor. Conecta el teléfono por USB: se enlazará solo.' 'Green'
    Say 'Para parar: cierra esta ventana o pulsa Ctrl+C.'
    uv run python -m server
}
