# Arrancar el asistente al iniciar sesión en Windows (o dejar de hacerlo).
#
#   Activar:    powershell -ExecutionPolicy Bypass -File scripts\autostart.ps1
#   Desactivar: powershell -ExecutionPolicy Bypass -File scripts\autostart.ps1 -Remove
#
# Crea (o borra) un acceso directo «Asistente» en la carpeta Inicio de tu usuario, que
# ejecuta scripts\start.ps1 -Hidden. No toca nada del sistema.

param([switch]$Remove)
$root = Split-Path -Parent $PSScriptRoot
$link = Join-Path ([Environment]::GetFolderPath('Startup')) 'Asistente.lnk'

if ($Remove) {
    if (Test-Path $link) { Remove-Item $link; Write-Host 'Arranque automático desactivado.' -ForegroundColor Green }
    else { Write-Host 'El arranque automático no estaba activado.' }
    exit 0
}

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($link)
$shortcut.TargetPath = 'powershell.exe'
$shortcut.Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$root\scripts\start.ps1`" -Hidden"
$shortcut.WorkingDirectory = $root
$shortcut.WindowStyle = 7  # minimizada
$shortcut.Description = 'Arranca el asistente personal al iniciar sesión'
$shortcut.Save()
Write-Host "Arranque automático activado: $link" -ForegroundColor Green
