param(
    [Parameter(Mandatory)][string]$BuildDir,
    [Parameter(Mandatory)][string]$Destination,
    [Parameter(Mandatory)][string]$KeyStore
)

$ErrorActionPreference = 'Stop'
if (!$env:ANDROID_HOME -or !$env:ZIQENO_SIGNING_PASSWORD) {
    throw 'ANDROID_HOME and ZIQENO_SIGNING_PASSWORD are required.'
}
$tools = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0'
$signer = Join-Path $tools $(if ($IsWindows) { 'apksigner.bat' } else { 'apksigner' })
$aligner = Join-Path $tools $(if ($IsWindows) { 'zipalign.exe' } else { 'zipalign' })
$aapt = Join-Path $tools $(if ($IsWindows) { 'aapt.exe' } else { 'aapt' })
$certificate = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release-certificate.sha256') -Raw).Trim()
if ($certificate -cnotmatch '^[0-9a-f]{64}$') {
    throw 'Invalid release certificate fingerprint.'
}
$KeyStore = (Resolve-Path -LiteralPath $KeyStore).Path
$BuildDir = (Resolve-Path -LiteralPath $BuildDir).Path
$metadata = Get-Content -LiteralPath (Join-Path $BuildDir 'output-metadata.json') -Raw | ConvertFrom-Json
$package = 'dev.ziqeno.android'
$abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')
$versions = @($metadata.elements.versionName | Select-Object -Unique)
$codes = @($metadata.elements.versionCode | Select-Object -Unique)
if ($metadata.applicationId -cne $package -or $metadata.variantName -cne 'release' -or
    $metadata.elements.Count -ne $abis.Count -or $versions.Count -ne 1 -or
    $versions[0] -cnotmatch '^\d+\.\d+\.\d+$' -or $codes.Count -ne 1 -or $codes[0] -le 0) {
    throw 'Unexpected release APK metadata.'
}
$version = $versions[0]
$versionCode = $codes[0]
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$Destination = (Resolve-Path -LiteralPath $Destination).Path

$fileNames = @($abis | ForEach-Object { "ZIQENO-$version-$_.apk" }) + 'SHA256SUMS.txt'
foreach ($name in $fileNames) {
    if (Test-Path -LiteralPath (Join-Path $Destination $name)) { throw "Output already exists: $name" }
}
$staging = (New-Item -ItemType Directory -Path (Join-Path $Destination (".signing-" + [Guid]::NewGuid()))).FullName
try {
    $checksums = foreach ($abi in $abis) {
        $entries = @($metadata.elements | Where-Object {
            $_.filters.Count -eq 1 -and $_.filters[0].filterType -ceq 'ABI' -and $_.filters[0].value -ceq $abi
        })
        if ($entries.Count -ne 1 -or $entries[0].outputFile -cnotmatch '^[a-zA-Z0-9_.-]+\.apk$') {
            throw "Missing or invalid release APK for $abi."
        }
        $source = Join-Path $BuildDir $entries[0].outputFile
        $name = "ZIQENO-$version-$abi.apk"
        $output = Join-Path $staging $name
        $aligned = Join-Path $staging "aligned-$abi.apk"
        & $aligner -P 16 4 $source $aligned
        if ($LASTEXITCODE) { throw "Alignment failed for $abi." }
        & $signer sign --ks $KeyStore --ks-key-alias ziqeno --ks-pass env:ZIQENO_SIGNING_PASSWORD --key-pass env:ZIQENO_SIGNING_PASSWORD --out $output $aligned
        if ($LASTEXITCODE) { throw "Signing failed for $abi." }
        $signature = (& $signer verify --print-certs $output) -join "`n"
        if ($LASTEXITCODE -or $signature -notmatch "certificate SHA-256 digest: $certificate\b") {
            throw "Unexpected signing certificate for $abi."
        }
        & $aligner -c -P 16 4 $output
        if ($LASTEXITCODE) { throw "The signed APK is not aligned for $abi." }
        $badging = (& $aapt dump badging $output) -join "`n"
        if ($LASTEXITCODE -or $badging -notmatch "package: name='$([regex]::Escape($package))' versionCode='$versionCode' versionName='$([regex]::Escape($version))'" -or
            $badging -notmatch "(?m)^native-code: '$([regex]::Escape($abi))'\s*$") {
            throw "APK contents do not match the release metadata for $abi."
        }
        "$((Get-FileHash -Algorithm SHA256 -LiteralPath $output).Hash.ToLowerInvariant())  $name"
    }
    $checksums | Set-Content -LiteralPath (Join-Path $staging 'SHA256SUMS.txt') -Encoding ascii
    foreach ($name in $fileNames) {
        Move-Item -LiteralPath (Join-Path $staging $name) -Destination $Destination
    }
} finally {
    Get-ChildItem -LiteralPath $staging -File -Force | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
    Remove-Item -LiteralPath $staging
}
[pscustomobject]@{ Version = $version; VersionCode = $versionCode }
