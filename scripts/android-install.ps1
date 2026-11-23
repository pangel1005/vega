# Compila la app Android (Vega), la instala en el teléfono por USB y le entrega el token.
#
#   powershell -ExecutionPolicy Bypass -File scripts\android-install.ps1 [-DebugBuild] [-BuildOnly]
#
# Busca solo lo que necesita, esté donde esté:
#   · Java 17 o más nuevo: JAVA_HOME, el que trae Android Studio, ~\.jdks o los de Microsoft/Adoptium/Oracle.
#   · El SDK de Android: ANDROID_HOME, ANDROID_SDK_ROOT, %LOCALAPPDATA%\Android\Sdk (Android Studio) o ~\Android\Sdk.
#   · La librería de voz del teléfono (sherpa-onnx, 50 MB): si falta, la baja (tools\android_sherpa.ps1).
#
# En el teléfono: Opciones de desarrollador → «Depuración USB» (y en Xiaomi también «Instalar vía USB»).
# Sin SIM, algunos Xiaomi no dejan instalar por USB: usa -BuildOnly y copia el APK al teléfono.

param([switch]$DebugBuild, [switch]$BuildOnly)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Get-JavaMajor([string]$home_) {
    $java = Join-Path $home_ 'bin\java.exe'
    if (-not (Test-Path $java)) { return 0 }
    $release = Join-Path $home_ 'release'
    if (Test-Path $release) {
        $line = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
        if ($line) { return [int]$line.Matches[0].Groups[1].Value }
    }
    return 0
}

function Find-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    )
    foreach ($dir in @("$env:USERPROFILE\.jdks", "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Eclipse Adoptium",
                       "$env:ProgramFiles\Java", "$env:ProgramFiles\Zulu")) {
        if (Test-Path $dir) {
            $candidates += Get-ChildItem $dir -Directory | Sort-Object Name -Descending | ForEach-Object FullName
        }
    }
    foreach ($c in $candidates) {
        if ($c -and (Get-JavaMajor $c) -ge 17) { return $c }
    }
    return $null
}

function Find-Sdk {
    foreach ($c in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", "$env:USERPROFILE\Android\Sdk")) {
        if (-not $c) { continue }
        if ((Test-Path (Join-Path $c 'platforms')) -or (Test-Path (Join-Path $c 'platform-tools'))) { return $c }
    }
    return $null
}

$jdk = Find-Jdk
if (-not $jdk) {
    throw @'
No encuentro Java 17 o más nuevo. Instálalo con:
    winget install Microsoft.OpenJDK.21
(o instala Android Studio, que ya lo trae) y vuelve a ejecutar este script.
'@
}
$sdk = Find-Sdk
if (-not $sdk) {
    throw @'
No encuentro el SDK de Android. Lo más fácil: instala Android Studio (winget install Google.AndroidStudio),
ábrelo una vez para que baje el SDK y vuelve a ejecutar este script. Si lo tienes en otra carpeta, pon su ruta
en la variable ANDROID_HOME.
'@
}
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:Path = "$jdk\bin;$env:Path"
Write-Host "Java: $jdk"
Write-Host "SDK de Android: $sdk"

# La librería de voz del teléfono no está en git (pesa 50 MB).
if (-not (Get-ChildItem (Join-Path $root 'android\app\libs') -Filter 'sherpa-onnx-*.aar' -ErrorAction SilentlyContinue)) {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $root 'tools\android_sherpa.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'No pude bajar la librería sherpa-onnx.' }
}

$variant = if ($DebugBuild) { 'Debug' } else { 'Release' }
Write-Host "Compilando ($variant)... la primera vez tarda varios minutos (baja Gradle y las librerías)."
Push-Location (Join-Path $root 'android')
try {
    & .\gradlew.bat "assemble$variant" --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw 'La compilación falló (mira los mensajes de arriba).' }
} finally { Pop-Location }

$apk = Get-ChildItem (Join-Path $root "android\app\build\outputs\apk\$($variant.ToLower())") -Filter '*.apk' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Write-Host "APK: $($apk.FullName) ($([math]::Round($apk.Length / 1MB, 1)) MB)"
if ($BuildOnly) { exit 0 }

$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $adb) { throw 'No encuentro adb: winget install Google.PlatformTools' }

$devices = @(& $adb devices | Select-String "`tdevice$")
if ($devices.Count -eq 0) {
    throw 'No veo ningún teléfono. Conéctalo por USB, activa «Depuración USB» y acepta el aviso en su pantalla.'
}

Write-Host 'Instalando en el teléfono...'
& $adb install -r $apk.FullName
if ($LASTEXITCODE -ne 0) {
    throw @'
El teléfono rechazó la instalación.
  · Xiaomi: Opciones de desarrollador → activa «Instalar vía USB».
  · Si ya tenías Vega instalada desde otro PC, desinstálala primero (la firma es distinta).
'@
}

$tokenFile = Join-Path $root 'config\token.txt'
if (Test-Path $tokenFile) {
    $token = (Get-Content $tokenFile -Raw).Trim()
    & $adb reverse tcp:8000 tcp:8000 | Out-Null
    # El token viaja en el arranque (no se guarda en la app compilada).
    & $adb shell am start -n com.asistente.personal/.MainActivity --es token $token | Out-Null
    Write-Host 'Listo: la app está abierta en el teléfono.' -ForegroundColor Green
} else {
    Write-Host 'App instalada. Arranca el servidor (scripts\start.ps1): al conectar el teléfono, la enlaza solo.' -ForegroundColor Green
}
