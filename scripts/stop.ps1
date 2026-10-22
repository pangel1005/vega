# Para el asistente y libera la GPU (saca el modelo de Ollama de la memoria de vídeo).
#
#   powershell -ExecutionPolicy Bypass -File scripts\stop.ps1

$server = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue
if ($server) {
    Stop-Process -Id $server.OwningProcess -Force
    # Espera a que el puerto quede libre: si no, un start.ps1 justo después creía que seguía en marcha.
    for ($i = 0; $i -lt 50 -and (Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue); $i++) {
        Start-Sleep -Milliseconds 200
    }
    Write-Host 'Servidor detenido.' -ForegroundColor Green
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
