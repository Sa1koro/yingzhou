param(
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$ReferenceCheckout,
    [string]$Maven = 'mvn.cmd',
    [string]$MavenRepository,
    [string]$SardineArchive,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$revision = '187c878fd3a8c1d379c8ef578f5825cba7bf0050'
$upstream = 'https://github.com/jo32/tvbox-silicon.git'
$destination = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $destination) { throw "Destination already exists: $destination" }
$stage = New-Item -ItemType Directory -Path "$destination.stage-$([Guid]::NewGuid())"
$source = Join-Path $stage.FullName 'source'
New-Item -ItemType Directory -Path $source | Out-Null
if (!$ReferenceCheckout) {
    if ($Offline) { throw '-Offline requires -ReferenceCheckout' }
    $ReferenceCheckout = Join-Path $stage.FullName 'checkout'
    git init -q $ReferenceCheckout
    if ($LASTEXITCODE) { throw 'Could not initialize source checkout' }
    git -C $ReferenceCheckout fetch --depth=1 $upstream $revision
    if ($LASTEXITCODE) { throw 'Could not fetch the pinned host revision' }
}
$reference = (Resolve-Path -LiteralPath $ReferenceCheckout).Path.Replace('\', '/')
$archive = Join-Path $stage.FullName 'upstream-source.tar'
git -c "safe.directory=$reference" -C $reference archive --format=tar --output=$archive $revision Runtime/JavaHost LICENSE Docs/THIRD_PARTY_NOTICES.md Scripts/build-java-host.sh
if ($LASTEXITCODE) { throw 'Could not export the pinned host source' }
tar.exe -xf $archive -C $source
if ($LASTEXITCODE) { throw 'Could not unpack the host source' }
$bundle = New-Item -ItemType Directory -Path (Join-Path $stage.FullName 'bundle')
$libraries = New-Item -ItemType Directory -Path (Join-Path $bundle.FullName 'lib')
$javaHost = Join-Path $source 'Runtime/JavaHost'
$runtime = Join-Path $javaHost 'src/main/java/tvbox/runtime'
$lazyDex = Join-Path $runtime 'LazyDexArchive.java'
$upstreamCall = "Dex2jar.from(selected).onlyClass(name.replace('.','/')).skipDebug(true).computeFrames(true).to(pending);"
$windowsCall = "WindowsDexOutput.write(Dex2jar.from(selected).onlyClass(name.replace('.','/')).skipDebug(true).computeFrames(true), pending, name);"
$upstreamText = [IO.File]::ReadAllText($lazyDex)
if ([regex]::Matches($upstreamText, [regex]::Escape($upstreamCall)).Count -ne 1) { throw 'Pinned LazyDexArchive no longer matches the Windows adaptation' }
[IO.File]::WriteAllText($lazyDex, $upstreamText.Replace($upstreamCall, $windowsCall))
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'yingxia/WindowsDexOutput.java') -Destination $runtime
$mavenArgs = @('-B', '-f', (Join-Path $javaHost 'pom.xml'), 'package', 'dependency:copy-dependencies', "-DoutputDirectory=$($libraries.FullName)")
if ($MavenRepository) { $mavenArgs += "-Dmaven.repo.local=$MavenRepository" }
if ($Offline) { $mavenArgs += '-o' }
& $Maven @mavenArgs
if ($LASTEXITCODE) { throw 'Could not build upstream JavaHost' }
if (!$SardineArchive) {
    if ($Offline) { throw '-Offline requires -SardineArchive' }
    $SardineArchive = Join-Path $stage.FullName 'sardine-android-0.9.aar'
    Invoke-WebRequest -Uri 'https://jitpack.io/com/github/thegrizzlylabs/sardine-android/0.9/sardine-android-0.9.aar' -OutFile $SardineArchive
}
if ((Get-FileHash -LiteralPath $SardineArchive -Algorithm SHA256).Hash -ne '6501973e47061e5de4a24d222fb1b459dd26ad3e02631afa06ecda45b7fd2dde') {
    throw 'Sardine Android checksum mismatch'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dependency = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $SardineArchive).Path)
try {
    [IO.Compression.ZipFileExtensions]::ExtractToFile($dependency.GetEntry('classes.jar'), (Join-Path $libraries.FullName 'sardine-android-0.9.jar'))
} finally { $dependency.Dispose() }
Copy-Item -LiteralPath (Join-Path $javaHost 'target/local-jar-host-0.1.0.jar') -Destination (Join-Path $bundle.FullName 'host.jar')
Copy-Item -LiteralPath (Join-Path $source 'LICENSE') -Destination (Join-Path $bundle.FullName 'LICENSE.txt')
Copy-Item -LiteralPath (Join-Path $source 'Docs/THIRD_PARTY_NOTICES.md') -Destination $bundle.FullName
$correspondingSource = Join-Path $bundle.FullName 'upstream-source.tar'
tar.exe -cf $correspondingSource -C $source Runtime/JavaHost/src Runtime/JavaHost/pom.xml Runtime/JavaHost/conversion-inputs.txt LICENSE Docs/THIRD_PARTY_NOTICES.md Scripts/build-java-host.sh
if ($LASTEXITCODE) { throw 'Could not archive the corresponding JavaHost source' }
[IO.File]::WriteAllText((Join-Path $bundle.FullName 'revision.txt'), "$revision`n")
Move-Item -LiteralPath $bundle.FullName -Destination $destination
