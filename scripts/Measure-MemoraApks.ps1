param(
    [Parameter(Mandatory)][string]$Arm64Apk,
    [Parameter(Mandatory)][string]$UniversalApk,
    [string]$BaselineApk,
    [Parameter(Mandatory)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'MemoraApk.psm1') -Force
$arm = Get-MemoraApkContents $Arm64Apk
$universal = Get-MemoraApkContents $UniversalApk
Assert-MemoraApkContents $arm @('arm64-v8a')
Assert-MemoraApkContents $universal @('arm64-v8a','armeabi-v7a','x86','x86_64')
Assert-MemoraApkPreservation -Baseline $universal -Optimized $arm
if ($BaselineApk) {
    $baseline = Get-MemoraApkContents $BaselineApk
    Assert-MemoraApkPreservation -Baseline $baseline -Optimized $universal
    foreach ($apk in @($arm,$universal)) {
        if ($apk.apkBytes -ge $baseline.apkBytes -or $apk.categories.'classes*.dex'.uncompressedBytes -ge $baseline.categories.'classes*.dex'.uncompressedBytes) { throw 'Optimized APK/code did not shrink against the same-source debug baseline.' }
    }
}
@{ arm64=$arm; universal=$universal } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding utf8
Write-Output "APK verification passed: ARM64 $($arm.apkBytes) bytes; universal $($universal.apkBytes) bytes. See $OutputPath."
