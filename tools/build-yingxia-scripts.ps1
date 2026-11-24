param(
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$ReferenceCheckout,
    [string]$BuildPython = 'python.exe',
    [string]$NodeArchive,
    [string]$PythonArchive,
    [string]$Wheelhouse
)

$ErrorActionPreference = 'Stop'
$revision = '187c878fd3a8c1d379c8ef578f5825cba7bf0050'
$destination = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $destination) { throw "Destination already exists: $destination" }
$stage = New-Item -ItemType Directory -Path "$destination.stage-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$bundle = New-Item -ItemType Directory -Path (Join-Path $stage.FullName 'bundle')
if (!$ReferenceCheckout) {
    $ReferenceCheckout = Join-Path $stage.FullName 'checkout'
    git init -q $ReferenceCheckout
    if ($LASTEXITCODE) { throw 'Could not initialize source checkout' }
    git -C $ReferenceCheckout fetch --depth=1 https://github.com/jo32/tvbox-silicon.git $revision
    if ($LASTEXITCODE) { throw 'Could not fetch the pinned host revision' }
}
$reference = (Resolve-Path -LiteralPath $ReferenceCheckout).Path.Replace('\', '/')
$sourceArchive = Join-Path $bundle.FullName 'upstream-source.tar'
git -c "safe.directory=$reference" -C $reference archive --format=tar --output=$sourceArchive $revision Runtime/ScriptHost Runtime/PythonHost Sources/TVCore/ScriptAssets/js Sources/TVCore/ScriptAssets/sites LICENSE Docs/THIRD_PARTY_NOTICES.md Scripts/build-script-host.sh Scripts/build-python-host.sh
if ($LASTEXITCODE) { throw 'Could not export the pinned host source' }
$source = New-Item -ItemType Directory -Path (Join-Path $stage.FullName 'source')
tar.exe -xf $sourceArchive -C $source.FullName
if ($LASTEXITCODE) { throw 'Could not unpack script host source' }

function Get-VerifiedArchive([string]$Path, [string]$Url, [string]$Sha256) {
    if (!$Path) {
        $Path = Join-Path $stage.FullName (Split-Path $Url -Leaf)
        Invoke-WebRequest -Uri $Url -OutFile $Path
    }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Sha256) { throw "Checksum mismatch for $Url" }
    return (Resolve-Path -LiteralPath $Path).Path
}

$nodeZip = Get-VerifiedArchive $NodeArchive 'https://nodejs.org/dist/v26.13.0/node-v26.13.0-win-x64.zip' 'dcc60d59a0746999f99064a01c020445d54d84228c691cdbf25b49ac3b59150a'
$pythonZip = Get-VerifiedArchive $PythonArchive 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip' '4acbed6dd1c744b0376e3b1cf57ce906f9dc9e95e68824584c8099a63025a3c3'
$scripts = Join-Path $bundle.FullName 'ScriptHost'
$pythonHost = Join-Path $bundle.FullName 'PythonHost'
Copy-Item -LiteralPath (Join-Path $source.FullName 'Runtime/ScriptHost') -Destination $scripts -Recurse
Copy-Item -LiteralPath (Join-Path $source.FullName 'Runtime/PythonHost') -Destination $pythonHost -Recurse
$assets = New-Item -ItemType Directory -Path (Join-Path $scripts 'assets')
Copy-Item -LiteralPath (Join-Path $source.FullName 'Sources/TVCore/ScriptAssets/js'),(Join-Path $source.FullName 'Sources/TVCore/ScriptAssets/sites') -Destination $assets.FullName -Recurse
Add-Type -AssemblyName System.IO.Compression.FileSystem
$node = [IO.Compression.ZipFile]::OpenRead($nodeZip)
try {
    [IO.Compression.ZipFileExtensions]::ExtractToFile($node.GetEntry('node-v26.13.0-win-x64/node.exe'), (Join-Path $scripts 'node.exe'))
    [IO.Compression.ZipFileExtensions]::ExtractToFile($node.GetEntry('node-v26.13.0-win-x64/LICENSE'), (Join-Path $scripts 'LICENSE.node'))
} finally { $node.Dispose() }
Expand-Archive -LiteralPath $pythonZip -DestinationPath (Join-Path $pythonHost 'python')
$buildVersion = & $BuildPython -I -c 'import sys,struct; print(f"{sys.version_info.major}.{sys.version_info.minor}-{8*struct.calcsize(chr(80))}")'
if ($LASTEXITCODE -or $buildVersion -ne '3.12-64') { throw 'BuildPython must be a 64-bit Python 3.12 with pip' }
$packages = Join-Path $pythonHost 'site-packages'
$pipArgs = @('-I', '-m', 'pip', '--isolated', 'install', '--disable-pip-version-check', '--no-compile', '--only-binary=:all:', '--target', $packages, '-r', (Join-Path $pythonHost 'requirements.txt'))
if ($Wheelhouse) { $pipArgs += @('--no-index', '--find-links', (Resolve-Path -LiteralPath $Wheelhouse).Path) }
& $BuildPython @pipArgs
if ($LASTEXITCODE) { throw 'Could not install the pinned PythonHost dependencies' }
& (Join-Path $pythonHost 'python/python.exe') -I -B -c "import sys; sys.path.insert(0,sys.argv[1]); import requests, lxml.etree, bs4, pyquery, Crypto.Cipher.AES, cryptography.hazmat.primitives.ciphers" $packages
if ($LASTEXITCODE) { throw 'Packaged Python could not import its dependencies' }
Copy-Item -LiteralPath (Join-Path $source.FullName 'LICENSE') -Destination (Join-Path $bundle.FullName 'LICENSE.txt')
Copy-Item -LiteralPath (Join-Path $source.FullName 'Docs/THIRD_PARTY_NOTICES.md') -Destination $bundle.FullName
[IO.File]::WriteAllText((Join-Path $bundle.FullName 'revision.txt'), "$revision`n")
Move-Item -LiteralPath $bundle.FullName -Destination $destination
