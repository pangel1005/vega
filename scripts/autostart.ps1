# Arrancar el asistente al iniciar sesión en Windows (o dejar de hacerlo).
#
#   Activar:    powershell -ExecutionPolicy Bypass -File scripts\autostart.ps1
#   Desactivar: powershell -ExecutionPolicy Bypass -File scripts\autostart.ps1 -Remove
#
# Crea (o borra) una tarea programada «Vega (asistente)» de tu usuario que, al iniciar
# sesión, ejecuta scripts\start.ps1 -Hidden. No necesita permisos de administrador.
#
# Antes era un acceso directo en la carpeta Inicio, pero Windows 11 no siempre lo
# ejecutaba (comprobado: tras apagar y encender, Vega no volvió). Si queda de una versión
# anterior, se borra.

param([switch]$Remove)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$task = 'Vega (asistente)'
$oldLink = Join-Path ([Environment]::GetFolderPath('Startup')) 'Asistente.lnk'

if (Test-Path $oldLink) { Remove-Item $oldLink }

if ($Remove) {
    if (Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $task -Confirm:$false
        Write-Host 'Arranque automático desactivado.' -ForegroundColor Green
    } else {
        Write-Host 'El arranque automático no estaba activado.'
    }
    exit 0
}

$user = "$env:USERDOMAIN\$env:USERNAME"
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -WorkingDirectory $root `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$root\scripts\start.ps1`" -Hidden"
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$trigger.Delay = 'PT15S'  # deja respirar al sistema justo al entrar
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 10) -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Settings $settings -Principal $principal `
    -Description 'Arranca Vega (asistente personal) al iniciar sesión' -Force | Out-Null
Write-Host "Arranque automático activado: tarea programada «$task»." -ForegroundColor Green
