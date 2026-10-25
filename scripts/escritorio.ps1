# Abre la app de escritorio de Vega (nativa, sin navegador).
#
#   powershell -ExecutionPolicy Bypass -File scripts\escritorio.ps1          # abre la ventana
#   powershell -ExecutionPolicy Bypass -File scripts\escritorio.ps1 -Bandeja # solo el icono de la bandeja
#   powershell -ExecutionPolicy Bypass -File scripts\escritorio.ps1 -Atajo   # crea un acceso «Vega» en el menú Inicio
#
# La primera vez instala sus dependencias (PySide6, ~300 MB): uv las guarda en el grupo «desktop».
param([switch]$Bandeja, [switch]$Atajo)
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [Environment]::GetEnvironmentVariable('Path', 'Machine')
if (-not $env:UV_PYTHON_INSTALL_DIR) { $env:UV_PYTHON_INSTALL_DIR = "$env:USERPROFILE\.uv\python" }
if (-not $env:UV_CACHE_DIR) { $env:UV_CACHE_DIR = "$env:USERPROFILE\.uv\cache" }

if ($Atajo) {
    $lnk = Join-Path ([Environment]::GetFolderPath('Programs')) 'Vega.lnk'
    $shell = New-Object -ComObject WScript.Shell
    $s = $shell.CreateShortcut($lnk)
    $s.TargetPath = 'powershell.exe'
    $s.Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$root\scripts\escritorio.ps1`""
    $s.WorkingDirectory = $root
    $s.Description = 'Vega: panel de escritorio'
    $s.Save()
    Write-Host "Acceso creado: $lnk" -ForegroundColor Green
    exit 0
}

$args2 = @('run', '--group', 'desktop', 'pythonw', '-m', 'desktop')
if ($Bandeja) { $args2 += '--tray' }
Start-Process -FilePath uv -ArgumentList $args2 -WorkingDirectory $root -WindowStyle Hidden
