# Para el asistente y libera la GPU (saca el modelo de Ollama de la memoria de vídeo).
#
#   powershell -ExecutionPolicy Bypass -File scripts\stop.ps1

$root = Split-Path -Parent $PSScriptRoot
$server = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue
function Wait-PortFree($tenths) {
    for ($i = 0; $i -lt $tenths -and (Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue); $i++) {
        Start-Sleep -Milliseconds 100
    }
    -not (Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue)
}
if ($server) {
    # Primero se le pide que se apague solo (suelta bien lo de Windows). Cerrarlo a la fuerza
    # dejó una vez el proceso medio muerto 2 minutos con el puerto ocupado.
    $clean = $false
    try {
        $token = (Get-Content (Join-Path $root 'config\token.txt') -Raw).Trim()
        Invoke-RestMethod 'http://127.0.0.1:8000/admin/apagar' -Method Post -Headers @{ 'x-token' = $token } -TimeoutSec 5 | Out-Null
        $clean = Wait-PortFree 80
    } catch { }
    if (-not $clean) {
        Stop-Process -Id $server.OwningProcess -Force -ErrorAction SilentlyContinue
        # Espera a que el puerto quede libre: si no, un start.ps1 justo después creía que seguía en marcha.
        Wait-PortFree 50 | Out-Null
    }
    Write-Host ('Servidor detenido' + $(if ($clean) { '.' } else { ' (a la fuerza).' })) -ForegroundColor Green
} else {
    Write-Host 'El servidor no estaba en marcha.'
}

$config = Get-Content (Join-Path (Split-Path -Parent $PSScriptRoot) 'config\assistant.toml') -Raw
$model = if ($config -match '(?m)^model\s*=\s*"([^"]+)"') { $Matches[1] } else { $null }
if ($model) {
    try {
        Invoke-RestMethod 'http://127.0.0.1:11434/api/generate' -Method Post -TimeoutSec 5 `
            -Body (@{ model = $model; keep_alive = 0 } | ConvertTo-Json) | Out-Null
        Write-Host "Modelo $model fuera de la GPU." -ForegroundColor Green
    } catch { }
}
