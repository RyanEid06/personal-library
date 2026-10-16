Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-MemoraApkContents {
    param([Parameter(Mandatory)][string]$ApkPath)
    $categories = [ordered]@{}
    foreach ($name in @('classes*.dex','lib/','res/','assets/','META-INF/','other')) {
        $categories[$name] = [ordered]@{ storedBytes=[long]0; uncompressedBytes=[long]0; count=0 }
    }
    $abis = @{}; $protectedFiles = @{}; $largest = @(); $aligned = $true; $arm64Libraries = 0
    $zip = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($ApkPath))
    try {
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName
            if ($name.EndsWith('/')) { continue }
            $category = if ($name -match '^classes(?:\d+)?\.dex$') { 'classes*.dex' }
                elseif ($name -match '^(lib|res|assets|META-INF)/') { $Matches[1] + '/' } else { 'other' }
            $categories[$category].storedBytes += $entry.CompressedLength
            $categories[$category].uncompressedBytes += $entry.Length
            $categories[$category].count++
            $largest += [pscustomobject]@{ name=$name; storedBytes=$entry.CompressedLength; uncompressedBytes=$entry.Length }
            if ($category -eq 'lib/' -or $category -eq 'assets/') {
                $stream = $entry.Open(); $sha = [Security.Cryptography.SHA256]::Create()
                try { $protectedFiles[$name] = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','') }
                finally { $sha.Dispose(); $stream.Dispose() }
            }
            if ($category -eq 'lib/') {
                if ($name -notmatch '^lib/([^/]+)/([^/]+\.so)$') { throw 'Unexpected native library path.' }
                $abi=$Matches[1]
                if (-not $abis.ContainsKey($abi)) { $abis[$abi] = [long]0 }
                $abis[$abi] += $entry.CompressedLength
                if ($abi -eq 'arm64-v8a') {
                    $arm64Libraries++
                    $stream = $entry.Open(); $memory = New-Object IO.MemoryStream
                    try { $stream.CopyTo($memory); $bytes=$memory.ToArray() }
                    finally { $memory.Dispose(); $stream.Dispose() }
                    if ($bytes.Length -lt 64 -or $bytes[0] -ne 127 -or $bytes[1] -ne 69 -or $bytes[2] -ne 76 -or $bytes[3] -ne 70 -or $bytes[4] -ne 2 -or $bytes[5] -ne 1) { throw 'Invalid ARM64 ELF header.' }
                    $offset=[BitConverter]::ToInt64($bytes,32)
                    $size=[BitConverter]::ToUInt16($bytes,54); $count=[BitConverter]::ToUInt16($bytes,56)
                    if ($offset -lt 0 -or $size -lt 56 -or $count -eq 0 -or $offset + [long]$size*$count -gt $bytes.Length) { throw 'Invalid ARM64 ELF program headers.' }
                    $loads=0
                    for ($i=0; $i -lt $count; $i++) {
                        $position=[int]($offset+$i*$size)
                        if ([BitConverter]::ToUInt32($bytes,$position) -eq 1) {
                            $loads++
                            $alignment=[BitConverter]::ToInt64($bytes,$position+48)
                            if ($alignment -lt 16384 -or $alignment % 16384 -ne 0) { $aligned=$false }
                        }
                    }
                    if ($loads -eq 0) { throw 'ARM64 ELF has no load segments.' }
                }
            }
        }
    } finally { $zip.Dispose() }
    $total=(Get-Item -LiteralPath $ApkPath).Length
    $stored=[long]0; foreach ($group in $categories.Values) { $stored += $group.storedBytes }
    [pscustomobject]@{
        apkPath=[IO.Path]::GetFullPath($ApkPath); apkBytes=$total
        apkSha256=(Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
        categories=$categories; overheadBytes=$total-$stored; abiStoredBytes=$abis
        nativeAbis=@($abis.Keys | Sort-Object); arm64ElfAligned=($aligned -and $arm64Libraries -gt 0)
        protectedFiles=$protectedFiles; largestFiles=@($largest | Sort-Object storedBytes -Descending | Select-Object -First 12)
        baselineBytes=112766321; reductionBytes=112766321-$total
        reductionPercent=[Math]::Round(100.0*(1.0-$total/112766321.0),2)
    }
}

function Assert-MemoraApkContents {
    param([Parameter(Mandatory)]$Contents,[Parameter(Mandatory)][string[]]$ExpectedAbis)
    if (@(Compare-Object @($Contents.nativeAbis | Sort-Object) @($ExpectedAbis | Sort-Object)).Count) { throw 'APK native ABI set does not match the required artifact.' }
    foreach ($abi in $ExpectedAbis) {
        foreach ($library in @('libmlkit_google_ocr_pipeline.so','libandroidx.graphics.path.so')) {
            if (-not $Contents.protectedFiles.ContainsKey("lib/$abi/$library")) { throw "Required native library is missing for $abi." }
        }
    }
    if (-not $Contents.arm64ElfAligned) { throw 'ARM64 native ELF load segments are not 16 KB aligned.' }
    if ($Contents.categories.'classes*.dex'.count -eq 0 -or $Contents.categories.'assets/'.count -eq 0) { throw 'APK code or bundled offline assets are missing.' }
}

function Assert-MemoraApkPreservation {
    param([Parameter(Mandatory)]$Baseline,[Parameter(Mandatory)]$Optimized)
    # AGP generates code-specific baseline profiles for optimized builds. These
    # are not offline models/content and legitimately differ from debug.
    $generatedProfiles=@('assets/dexopt/baseline.prof','assets/dexopt/baseline.profm')
    $expected=@($Baseline.protectedFiles.Keys | Where-Object { $_ -notin $generatedProfiles -and ($_ -like 'assets/*' -or ($_.Split('/')[1] -in $Optimized.nativeAbis)) } | Sort-Object)
    $actual=@($Optimized.protectedFiles.Keys | Where-Object { $_ -notin $generatedProfiles } | Sort-Object)
    if (@(Compare-Object $expected $actual).Count) { throw 'Native libraries or offline asset set changed.' }
    foreach ($name in $expected) {
        if ($Baseline.protectedFiles[$name] -ne $Optimized.protectedFiles[$name]) { throw 'A native library or offline asset changed.' }
    }
}

Export-ModuleMember -Function Get-MemoraApkContents,Assert-MemoraApkContents,Assert-MemoraApkPreservation
