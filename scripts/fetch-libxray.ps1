param([string]$ArchivePath)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression

$release = 'v26.10.31'
$sha256 = 'fb99f04acf379cc2957c1e1d0ce2b3c0005d3f49974a44c09a11242a0562c1ca'
$project = Split-Path -Parent $PSScriptRoot
$libs = Join-Path $project 'androidApp/libs'
$downloadPath = Join-Path $libs "libxray-$release-$([Guid]::NewGuid()).zip"
$aarPath = Join-Path $libs 'libXray.aar'
$stagedAar = Join-Path $libs "libXray-$([Guid]::NewGuid()).aar"

New-Item -ItemType Directory -Force -Path $libs | Out-Null
try {
    if (!$ArchivePath) {
        Invoke-WebRequest -Uri "https://github.com/XTLS/libXray/releases/download/$release/libxray-android.zip" -OutFile $downloadPath
        $ArchivePath = $downloadPath
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash -ne $sha256) {
        throw 'libXray archive checksum mismatch.'
    }

    $stream = [IO.File]::OpenRead($archivePath)
    $archive = [IO.Compression.ZipArchive]::new($stream)
    try {
        $entry = @($archive.Entries | Where-Object { $_.Name -ceq 'libXray.aar' })
        if ($entry.Count -ne 1) { throw 'Expected one libXray.aar in the official release.' }
        $inputStream = $entry[0].Open()
        try {
            $outputStream = [IO.File]::Create($stagedAar)
            try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
        } finally {
            $inputStream.Dispose()
        }
    } finally {
        $archive.Dispose()
        $stream.Dispose()
    }
    Move-Item -LiteralPath $stagedAar -Destination $aarPath -Force
} finally {
    foreach ($temporary in @($downloadPath, $stagedAar)) {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}
Write-Output "Prepared libXray $release."
