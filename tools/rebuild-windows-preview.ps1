param(
    [Parameter(Mandatory)][string]$BaseDirectory,
    [Parameter(Mandatory)][string]$FlutterSdk
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$base = Get-Item -LiteralPath $BaseDirectory
$sdk = (Resolve-Path -LiteralPath $FlutterSdk).Path
$flutter = Join-Path $sdk 'bin/flutter.bat'
$releaseEngine = Join-Path $sdk 'bin/cache/artifacts/engine/windows-x64-release'
$previewName = "$($base.Name)-startup-fixed"
$preview = Join-Path $base.Parent.FullName $previewName
$zip = "$preview.zip"

foreach ($path in @($preview, $zip, "$zip.sha256")) {
    if (Test-Path -LiteralPath $path) { throw "Destination already exists: $path" }
}
foreach ($file in @('yingzhou.exe', 'data/app.so', 'data/flutter_assets/NativeAssetsManifest.json', 'BUILD-INFO.txt')) {
    if (!(Test-Path -LiteralPath (Join-Path $base.FullName $file) -PathType Leaf)) {
        throw "The base preview is missing $file"
    }
}
$baseEngine = Get-FileHash -LiteralPath (Join-Path $base.FullName 'flutter_windows.dll')
$sdkEngine = Get-FileHash -LiteralPath (Join-Path $releaseEngine 'flutter_windows.dll')
if ($baseEngine.Hash -ne $sdkEngine.Hash) { throw 'The Flutter SDK engine does not match the base preview.' }

$client = Join-Path $root 'client'
$assembleOutput = Join-Path $root "engine/build/windows-preview-aot/$([Guid]::NewGuid())"
Push-Location -LiteralPath $client
try {
    & $flutter assemble "--output=$assembleOutput" '--define=TargetPlatform=windows-x64' `
        '--define=BuildMode=release' '--define=TargetFile=lib/main.dart' aot_elf_release
    if ($LASTEXITCODE) { throw 'Flutter AOT build failed.' }
} finally {
    Pop-Location
}

$buildId = (Get-Content -LiteralPath (Join-Path $assembleOutput '.last_build_id') -Raw).Trim()
if ($buildId -notmatch '^[a-f0-9]{32}$') { throw 'Flutter did not report a valid build ID.' }
$snapshot = Join-Path $client ".dart_tool/flutter_build/$buildId/app.so"
$snapshotText = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($snapshot))
foreach ($marker in @('dart_plugin_registrant.dart', 'package:shared_preferences_windows/', 'package:path_provider_windows/')) {
    if (!$snapshotText.Contains($marker)) { throw "Windows preview AOT is missing $marker" }
}

$snapshotHash = (Get-FileHash -LiteralPath $snapshot).Hash.ToLowerInvariant()
$buildInfo = Get-Content -LiteralPath (Join-Path $base.FullName 'BUILD-INFO.txt') -Raw
$buildInfo = $buildInfo -replace '(?m)^Client AOT .*$', 'Client AOT compiled with Flutter assemble aot_elf_release (windows-x64, release, lib/main.dart).'
$buildInfo = $buildInfo -replace '(?m)^[a-fA-F0-9]{64}  data/app\.so\r?$', "$snapshotHash  data/app.so"

Copy-Item -LiteralPath $base.FullName -Destination $preview -Recurse
Copy-Item -LiteralPath $snapshot -Destination (Join-Path $preview 'data/app.so') -Force
Set-Content -LiteralPath (Join-Path $preview 'BUILD-INFO.txt') -Value $buildInfo.TrimEnd() -Encoding utf8

& tar.exe -a -cf $zip -C $base.Parent.FullName $previewName
if ($LASTEXITCODE) { throw 'Windows preview ZIP creation failed.' }
$zipHash = (Get-FileHash -LiteralPath $zip).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$zip.sha256" -Value "$zipHash  $previewName.zip" -Encoding ascii
Write-Output "$zipHash  $zip"
