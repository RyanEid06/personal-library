$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'MemoraApk.psm1') -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$directory = Join-Path ([IO.Path]::GetTempPath()) ('memora-apk-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory | Out-Null
$passed = 0
function Require($condition) { if (-not $condition) { throw 'APK regression assertion failed.' } }
function Reject([scriptblock]$operation) { $failed=$false; try { & $operation } catch { $failed=$true }; Require $failed }
function Fixture([string]$name,[string[]]$abis,[switch]$MissingOcr,[switch]$Unaligned) {
    $path = Join-Path $directory $name
    $zip = [IO.Compression.ZipFile]::Open($path,'Create')
    try {
        foreach ($abi in $abis) {
            foreach ($library in @('libandroidx.graphics.path.so','libmlkit_google_ocr_pipeline.so')) {
                if ($MissingOcr -and $library -like '*ocr*') { continue }
                $bytes = New-Object byte[] 128
                [byte[]]@(127,69,76,70,2,1) | ForEach-Object -Begin { $i=0 } -Process { $bytes[$i++]=$_ }
                [BitConverter]::GetBytes([long]64).CopyTo($bytes,32)
                [BitConverter]::GetBytes([uint16]56).CopyTo($bytes,54)
                [BitConverter]::GetBytes([uint16]1).CopyTo($bytes,56)
                [BitConverter]::GetBytes([uint32]1).CopyTo($bytes,64)
                [BitConverter]::GetBytes([long]$(if ($Unaligned) {4096} else {16384})).CopyTo($bytes,112)
                $entry=$zip.CreateEntry("lib/$abi/$library"); $stream=$entry.Open()
                try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
            }
        }
        foreach ($name in @('classes.dex','assets/mlkit-google-ocr-models/model.bin','res/drawable/icon.png')) {
            $entry=$zip.CreateEntry($name); $writer=New-Object IO.StreamWriter($entry.Open())
            try { $writer.Write('synthetic test content') } finally { $writer.Dispose() }
        }
    } finally { $zip.Dispose() }
    return $path
}
try {
    $arm = Fixture 'arm.apk' @('arm64-v8a')
    $all = Fixture 'all.apk' @('arm64-v8a','armeabi-v7a','x86','x86_64')
    $result = Get-MemoraApkContents -ApkPath $arm
    Assert-MemoraApkContents -Contents $result -ExpectedAbis @('arm64-v8a')
    Require ($result.apkBytes -eq (Get-Item $arm).Length)
    Require ($result.apkSha256 -eq (Get-FileHash $arm -Algorithm SHA256).Hash)
    Require ($result.categories.'classes*.dex'.uncompressedBytes -eq 22)
    Require ($result.arm64ElfAligned -eq $true)
    $passed++
    Assert-MemoraApkContents -Contents (Get-MemoraApkContents $all) -ExpectedAbis @('arm64-v8a','armeabi-v7a','x86','x86_64')
    $passed++
    Reject { Assert-MemoraApkContents -Contents (Get-MemoraApkContents $all) -ExpectedAbis @('arm64-v8a') }; $passed++
    Reject { Assert-MemoraApkContents -Contents $result -ExpectedAbis @('arm64-v8a','armeabi-v7a','x86','x86_64') }; $passed++
    Reject { Assert-MemoraApkContents -Contents (Get-MemoraApkContents (Fixture 'missing.apk' @('arm64-v8a') -MissingOcr)) -ExpectedAbis @('arm64-v8a') }; $passed++
    Reject { Assert-MemoraApkContents -Contents (Get-MemoraApkContents (Fixture 'unaligned.apk' @('arm64-v8a') -Unaligned)) -ExpectedAbis @('arm64-v8a') }; $passed++
    Assert-MemoraApkPreservation -Baseline (Get-MemoraApkContents $all) -Optimized $result
    $passed++
    $profiled = Get-MemoraApkContents $arm
    $profiled.protectedFiles['assets/dexopt/baseline.prof'] = '1' * 64
    Assert-MemoraApkPreservation -Baseline (Get-MemoraApkContents $all) -Optimized $profiled
    $passed++
    $changed = Get-MemoraApkContents $arm
    $changed.protectedFiles['assets/mlkit-google-ocr-models/model.bin'] = '0' * 64
    Reject { Assert-MemoraApkPreservation -Baseline (Get-MemoraApkContents $all) -Optimized $changed }; $passed++
    Write-Output "Memora APK content verification: $passed tests passed. Synthetic ZIPs only."
} finally {
    $resolved = [IO.Path]::GetFullPath($directory)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'memora-apk-test-*') { throw 'Test cleanup containment failed.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
