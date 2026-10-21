# Compila la app Android, la instala en el teléfono por USB y le entrega el token.
#
#   powershell -ExecutionPolicy Bypass -File scripts\android-install.ps1 [-DebugBuild]
#
# Por defecto compila la versión optimizada (release). Sin SIM, Xiaomi no deja instalar
# por USB: en ese caso basta con compilar y pulsar ACTUALIZAR en la app.
#
# Requisitos (una sola vez): JDK 21 en ~\.jdks, Android SDK en ~\Android\Sdk y, en el
# teléfono Xiaomi, «Depuración USB» e «Instalar vía USB» activados.

param([switch]$DebugBuild)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-21*' | Select-Object -First 1
if (-not $jdk) { throw "No encuentro el JDK 21 en $env:USERPROFILE\.jdks" }
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = "$env:USERPROFILE\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
}
if (-not $adb) { throw 'No encuentro adb.' }

$tokenFile = Join-Path $root 'config\token.txt'
if (-not (Test-Path $tokenFile)) { throw 'Falta config\token.txt: arranca el servidor una vez para crearlo.' }
$token = (Get-Content $tokenFile -Raw).Trim()

$variant = if ($DebugBuild) { 'Debug' } else { 'Release' }
Write-Host "Compilando ($variant)..."
Push-Location (Join-Path $root 'android')
try {
    & .\gradlew.bat "assemble$variant" --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw 'La compilación falló.' }
} finally { Pop-Location }

$apk = Get-ChildItem (Join-Path $root "android\app\build\outputs\apk\$($variant.ToLower())") -Filter '*.apk' | Select-Object -First 1
Write-Host "Instalando $($apk.Name) ($([math]::Round($apk.Length / 1MB, 1)) MB)..."
& $adb install -r $apk.FullName
if ($LASTEXITCODE -ne 0) {
    throw 'El teléfono rechazó la instalación. En Xiaomi: Opciones de desarrollador → activa «Instalar vía USB».'
}

& $adb reverse tcp:8000 tcp:8000 | Out-Null
# El token viaja en el arranque (no se guarda en la app compilada).
& $adb shell am start -n com.asistente.personal/.MainActivity --es token $token | Out-Null
Write-Host 'Listo: la app está abierta en el teléfono.'
