# Compila el modelo de lenguaje del teléfono (llama.cpp + puente) para la app Android.
#
#   powershell -ExecutionPolicy Bypass -File tools\android_llama.ps1
#
# Necesita el NDK 30 y cmake 4.1.2 del SDK de Android, y el código de llama.cpp en una ruta
# corta (por defecto ~\vb\llama.cpp: con rutas largas Windows pasa de 250 caracteres).
# Deja libvegallm.so en android\app\src\main\jniLibs\arm64-v8a (fuera de git).
param([string]$Llama = "$env:USERPROFILE\vb\llama.cpp")
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = "$env:USERPROFILE\Android\Sdk"
$cmake = "$sdk\cmake\4.1.2\bin\cmake.exe"
$build = "$env:USERPROFILE\vb\vegallm"
if (-not (Test-Path "$Llama\include\llama.h")) {
    throw "No encuentro llama.cpp en $Llama (git clone https://github.com/ggml-org/llama.cpp)"
}
# Entre comillas va «-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod»: sin ellas Windows PowerShell 5.1 parte el
# argumento en el punto de «armv8.2-a» y la caché de CMake se queda en «armv8» (sin dotprod: la
# lectura del modelo iba 2,5 veces más lenta; medido el 25-10). Los núcleos del Redmi 15C tienen dotprod.
& $cmake -S "$root\android\native" -B $build -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$sdk\cmake\4.1.2\bin\ninja.exe" `
    "-DCMAKE_TOOLCHAIN_FILE=$sdk\ndk\30.0.16248370\build\cmake\android.toolchain.cmake" `
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static `
    -DCMAKE_BUILD_TYPE=Release "-DLLAMA_DIR=$Llama" `
    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod"
if ($LASTEXITCODE) { throw 'Falló la configuración' }
& $cmake --build $build --target vegallm -j 8
if ($LASTEXITCODE) { throw 'Falló la compilación' }
$dest = "$root\android\app\src\main\jniLibs\arm64-v8a"
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item "$build\libvegallm.so" $dest -Force
Write-Host "Listo: $dest\libvegallm.so" -ForegroundColor Green
