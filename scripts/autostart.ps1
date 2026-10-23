# Arrancar el asistente al iniciar sesión en Windows (o dejar de hacerlo).
#
#   Activar:    powershell -ExecutionPolicy Bypass -File scripts\autostart.ps1
#   Desactivar: powershell -ExecutionPolicy Bypass -File scripts\autostart.ps1 -Remove
#
# Crea (o borra) dos tareas programadas de tu usuario (sin permisos de administrador):
#   «Vega (asistente)»: al iniciar sesión, ejecuta scripts\start.ps1 -Hidden.
#   «Vega (vigilante)»: cada 5 minutos, si Vega está en marcha pero colgada, la reinicia
#                        (scriptsigilante.ps1; una noche se quedó colgada horas).
#
# Antes era un acceso directo en la carpeta Inicio, pero Windows 11 no siempre lo
# ejecutaba (comprobado: tras apagar y encender, Vega no volvió). Si queda de una versión
# anterior, se borra.

param([switch]$Remove)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$task = 'Vega (asistente)'
$watch = 'Vega (vigilante)'
$oldLink = Join-Path ([Environment]::GetFolderPath('Startup')) 'Asistente.lnk'

if (Test-Path $oldLink) { Remove-Item $oldLink }

if ($Remove) {
    if (Get-ScheduledTask -TaskName $watch -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $watch -Confirm:$false
    }
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
$watchAction = New-ScheduledTaskAction -Execute 'powershell.exe' -WorkingDirectory $root `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$root\scripts\vigilante.ps1`""
$watchTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(5) -RepetitionInterval (New-TimeSpan -Minutes 5)
$watchSettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 4) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $watch -Action $watchAction -Trigger $watchTrigger -Settings $watchSettings `
    -Principal $principal -Description 'Reinicia Vega si se queda colgada' -Force | Out-Null
Write-Host "Arranque automático activado: tareas «$task» y «$watch»." -ForegroundColor Green
