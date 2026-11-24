# Instala Vega en este PC con un solo comando. Se puede repetir: lo que ya está hecho se salta.
#
#   powershell -ExecutionPolicy Bypass -File scripts\instalar.ps1
#
# Opciones:
#   -SinEscritorio   no instala la app de escritorio (ventana con ajustes, botones y estado)
#   -SinArranque     no pregunta si Vega debe arrancar sola al encender el PC
#   -SinTelefono     no pregunta por la app del teléfono
#
# Qué hace:
#   1. Instala con winget lo que falte: uv (Python), Ollama (la IA) y las herramientas de Android (adb).
#   2. Instala las dependencias de Python (uv sync).
#   3. Crea tus ajustes según tu tarjeta gráfica, baja el modelo de lenguaje (~3 GB) y las voces (~120 MB).
#   4. Te pregunta si Vega arranca sola con Windows y si instalamos la app en tu teléfono.

param([switch]$SinEscritorio, [switch]$SinArranque, [switch]$SinTelefono)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Say($text, $color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
}
function Ask($question) {
    $answer = Read-Host "$question [S/n]"
    return ($answer -eq '' -or $answer -match '^(s|si|sí|y|yes)$')
}

Say 'Instalador de Vega' 'Green'
Say 'Tarda entre 5 y 20 minutos según tu internet (baja unos 4 GB la primera vez).'

# --- 1. Programas ---
Step '1/4 · Programas necesarios'
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw 'Falta winget («Instalador de aplicación» de la Microsoft Store). Instálalo y vuelve a ejecutar este script.'
}
$needed = @(
    @{ Id = 'astral-sh.uv'; Check = { Get-Command uv -ErrorAction SilentlyContinue }; What = 'uv (instala Python solo)' },
    @{ Id = 'Ollama.Ollama'; Check = { (Get-Command ollama -ErrorAction SilentlyContinue) -or (Test-Path "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe") }; What = 'Ollama (el modelo de lenguaje)' },
    @{ Id = 'Google.PlatformTools'; Check = { Get-Command adb -ErrorAction SilentlyContinue }; What = 'adb (para hablar con el teléfono)' }
)
foreach ($p in $needed) {
    if (& $p.Check) { Say "  ✓ $($p.What)" 'Green'; continue }
    Say "  Instalando $($p.What)..."
    winget install --id $p.Id --exact --silent --accept-package-agreements --accept-source-agreements | Out-Host
    Refresh-Path
    if (-not (& $p.Check)) {
        Say "  Instalado, pero aún no aparece en esta ventana. Si algo falla, cierra la ventana y ejecuta el instalador otra vez." 'Yellow'
    }
}
Refresh-Path

# --- 2. Python y dependencias ---
Step '2/4 · Dependencias de Python'
$uvArgs = @('sync')
if (-not $SinEscritorio) { $uvArgs += @('--group', 'desktop') }
& uv @uvArgs
if ($LASTEXITCODE -ne 0) { throw 'uv sync falló (mira los mensajes de arriba).' }

# --- 3. Ajustes, modelo y voces ---
Step '3/4 · Tus ajustes, el modelo y las voces'
function Test-Ollama {
    try { Invoke-RestMethod 'http://127.0.0.1:11434/api/version' -TimeoutSec 2 | Out-Null; return $true } catch { return $false }
}
if (-not (Test-Ollama)) {
    $app = "$env:LOCALAPPDATA\Programs\Ollama\ollama app.exe"
    if (Test-Path $app) { Start-Process -FilePath $app -WindowStyle Hidden }
    else { Start-Process -FilePath 'ollama' -ArgumentList 'serve' -WindowStyle Hidden }
    for ($i = 0; $i -lt 60 -and -not (Test-Ollama); $i++) { Start-Sleep 1 }
}
& uv run python -m server.tools.install
if ($LASTEXITCODE -ne 0) { throw 'La preparación falló (mira los mensajes de arriba).' }

# --- 4. Arranque y teléfono ---
Step '4/4 · Arranque y teléfono'
if (-not $SinArranque) {
    if (Ask '¿Quieres que Vega arranque sola al encender el PC?') {
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'autostart.ps1')
    } else {
        Say '  Vale. Para arrancarla a mano: scripts\start.ps1'
    }
}
if (-not $SinTelefono) {
    Say ''
    Say 'La app del teléfono: activa en el teléfono las «Opciones de desarrollador» y la «Depuración USB»,'
    Say 'conéctalo por USB y acepta el aviso que sale en su pantalla.'
    if (Ask '¿Instalamos ahora la app en el teléfono? (necesita Java y el SDK de Android; si faltan, te dice cómo)') {
        try {
            & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'android-install.ps1')
        } catch {
            Say "  $($_.Exception.Message)" 'Yellow'
            Say '  Puedes repetirlo cuando quieras: scripts\android-install.ps1' 'Yellow'
        }
    }
}

Write-Host ''
Say 'Vega está instalada.' 'Green'
Say '  · Arrancar:            scripts\start.ps1   (o se arranca sola si lo elegiste)'
if (-not $SinEscritorio) { Say '  · App de escritorio:   scripts\escritorio.ps1' }
Say '  · Teléfono:           conéctalo por USB con el servidor en marcha: se enlaza y abre la app solo.'
Say '  · Telegram y Moodle:  uv run python -m server.tools.telegram_setup  /  server.tools.moodle_setup'
