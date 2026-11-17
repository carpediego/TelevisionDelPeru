# Compila esta version de la app usando un JDK 21 portable.
# Uso:   .\build.ps1            -> APK release firmado
#        .\build.ps1 -Debug     -> APK debug (para pruebas)
param([switch]$Debug)

$ErrorActionPreference = "Stop"

# Busca el JDK en el proyecto, en la carpeta padre o en el toolchain de Radio.
$roots = @(
    (Join-Path $PSScriptRoot ".toolchain"),
    (Join-Path (Split-Path $PSScriptRoot -Parent) ".toolchain"),
    "X:\APPS CREADAS\Radio\.toolchain"
)

$jdk = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $jdk = Get-Item $env:JAVA_HOME
} else {
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $jdk = Get-ChildItem -Path $root -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($jdk) { break }
    }
}

if (-not $jdk) {
    throw "No se encontro un JDK 21. Copia el toolchain a '.toolchain' o define JAVA_HOME. Rutas revisadas: $($roots -join ', ')"
}

$env:JAVA_HOME = $jdk.FullName
Write-Host "JDK: $($jdk.Name)" -ForegroundColor DarkGray

if (-not $Debug -and -not (Test-Path (Join-Path $PSScriptRoot "keystore\keystore.properties"))) {
    throw "Falta keystore\keystore.properties: un release sin firmar no sirve. Ejecuta primero la generacion de la keystore (ver README)."
}

$task = if ($Debug) { "assembleDebug" } else { "assembleRelease" }

# Gradle escribe avisos por stderr; no deben tratarse como error de PowerShell.
$ErrorActionPreference = "Continue"
$output = & (Join-Path $PSScriptRoot "gradlew.bat") $task "-p" $PSScriptRoot --console=plain 2>&1
$code = $LASTEXITCODE
$output | ForEach-Object { Write-Host $_.ToString() }
$ErrorActionPreference = "Stop"

if ($code -ne 0) { throw "La compilacion fallo (codigo $code)" }

$relative = if ($Debug) {
    "app\build\outputs\apk\debug\app-debug.apk"
} else {
    "app\build\outputs\apk\release\app-release.apk"
}

$apk = Join-Path $PSScriptRoot $relative
if (-not (Test-Path $apk)) { throw "No se genero el APK esperado: $relative" }

$dist = Join-Path $PSScriptRoot "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$suffix = if ($Debug) { "debug" } else { "release" }
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$target = Join-Path $dist "TelevisionDelPeru-$stamp-$suffix.apk"
Copy-Item $apk $target -Force

$mb = [math]::Round((Get-Item $target).Length / 1MB, 2)
Write-Host ""
Write-Host "APK listo: $target ($mb MB)" -ForegroundColor Green
