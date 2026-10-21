# Desactiva publicidad y apps preinstaladas de HyperOS que no hacen falta (libera RAM).
# Es REVERSIBLE: las apps no se borran del teléfono, solo se quitan para tu usuario.
#
#   Desactivar: powershell -ExecutionPolicy Bypass -File scripts\android-debloat.ps1
#   Restaurar:  powershell -ExecutionPolicy Bypass -File scripts\android-debloat.ps1 -Restore
#
# Lista conservadora: nada del sistema, la cámara, la galería, los ajustes, la cuenta Mi
# ni la comunidad Xiaomi (necesaria si algún día se desbloquea el teléfono).

param([switch]$Restore)

$adb = "$env:USERPROFILE\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $adb) { throw 'No encuentro adb.' }

$packages = [ordered]@{
    'com.miui.msa.global'               = 'Publicidad del sistema (MSA)'
    'com.miui.analytics'                = 'Analíticas de Xiaomi'
    'com.miui.android.fashiongallery'   = 'Carrusel de fondos con anuncios'
    'com.xiaomi.mipicks'                = 'Tienda GetApps (anuncios)'
    'com.mi.global.shop'                = 'Tienda Xiaomi'
    'com.xiaomi.glgm'                   = 'Centro de juegos'
    'com.block.puzzle.game.hippo.mi'    = 'Juego preinstalado'
    'com.facebook.appmanager'           = 'Facebook App Manager'
    'com.facebook.services'             = 'Facebook Services'
    'com.facebook.system'               = 'Facebook App Installer'
    'com.miui.yellowpage'               = 'Páginas amarillas'
    'com.mi.globalminusscreen'          = 'Panel de widgets (App Vault)'
}

# HyperOS no deja «desactivar» apps de sistema, pero sí quitarlas solo para el usuario 0
# (`pm uninstall -k --user 0`). El APK sigue en el teléfono: `install-existing` la devuelve.
foreach ($pkg in $packages.Keys) {
    $cmd = if ($Restore) { "cmd package install-existing $pkg" } else { "pm uninstall -k --user 0 $pkg" }
    $out = (& $adb shell $cmd 2>&1 | Select-Object -First 1) -join ''
    $ok = $out -match 'Success|installed for user'
    $state = if ($ok) { 'OK' } elseif ($out -match 'not installed') { 'ya estaba quitada' } else { "no se pudo: $out" }
    Write-Host ('{0,-34} {1,-34} {2}' -f $pkg, $packages[$pkg], $state)
}
