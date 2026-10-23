# Vigilante: si Vega está en marcha pero no responde (se quedó colgada), la reinicia.
#
#   powershell -ExecutionPolicy Bypass -File scripts\vigilante.ps1
#
# Lo lanza cada 5 minutos la tarea «Vega (vigilante)» que crea scripts\autostart.ps1.
# Si Vega está parada (por ejemplo, la paraste tú con stop.ps1), no hace nada.
# Deja lo que hace en logs\vigilante.log.

$root = Split-Path -Parent $PSScriptRoot
$log = "$root\logs\vigilante.log"

$server = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $server) { exit 0 }  # parada a propósito: no es cosa del vigilante

function Test-Vega {
    try { Invoke-RestMethod 'http://127.0.0.1:8000/health' -TimeoutSec 10 | Out-Null; return $true } catch { return $false }
}

# Dos intentos separados: un momento de mucho trabajo no es un cuelgue.
if (Test-Vega) { exit 0 }
Start-Sleep 20
if (Test-Vega) { exit 0 }

New-Item -ItemType Directory -Force "$root\logs" | Out-Null
Add-Content $log "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') Vega no responde (proceso $($server.OwningProcess)): la reinicio."
taskkill /PID $server.OwningProcess /T /F | Out-Null
for ($i = 0; $i -lt 50 -and (Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue); $i++) {
    Start-Sleep -Milliseconds 200
}
& powershell -NoProfile -ExecutionPolicy Bypass -File "$root\scripts\start.ps1" -Hidden | Out-Null
$state = if (Test-Vega) { 'Vega vuelve a responder.' } else { 'Vega sigue sin responder: mira logs\server.log.' }
Add-Content $log "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $state"
