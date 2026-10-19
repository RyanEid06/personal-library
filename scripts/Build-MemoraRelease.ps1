param(
    [ValidatePattern('^[0-9a-f]{40}$')][string]$VerifiedSourceSha,
    [string]$AndroidSdkRoot = $env:ANDROID_HOME,
    [string]$OutputDirectory = [Environment]::GetFolderPath('Desktop'),
    [switch]$QaCandidate
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $PSScriptRoot 'MemoraSigning.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'MemoraApk.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'MemoraReleasePolicy.psm1') -Force
$identity = Get-MemoraSigningIdentity
$canonicalKey = $identity.KeystorePath
$expectedKeyHash = $identity.KeystoreSha256
$expectedCert = $identity.CertificateSha256
$environmentNames = @('RELEASE_KEYSTORE_PATH','RELEASE_STORE_PASSWORD','RELEASE_KEY_ALIAS','RELEASE_KEY_PASSWORD','ANDROID_HOME','JAVA_TOOL_OPTIONS')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$credential = $null
Push-Location $repo
try {
    if ($QaCandidate -and (-not $VerifiedSourceSha -or $PSBoundParameters.ContainsKey('OutputDirectory'))) { throw 'QA candidate mode requires -VerifiedSourceSha and uses its fixed repository artifact directory; do not pass -OutputDirectory.' }
    if (-not $VerifiedSourceSha) {
        $VerifiedSourceSha = (& git rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0 -or $VerifiedSourceSha -notmatch '^[0-9a-f]{40}$') { throw 'Cannot resolve the source SHA.' }
    }
    $headSha=(& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify checkout HEAD.' }
    $branch=(& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify checkout branch.' }
    $status=@(& git -c core.quotepath=false status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify checkout status.' }
    Assert-MemoraReleaseCheckout -QaCandidate:$QaCandidate -Branch $branch -HeadSha $headSha -VerifiedSourceSha $VerifiedSourceSha -Status $status
    & git fetch origin
    if ($LASTEXITCODE -ne 0) { throw 'Cannot fetch the verified source.' }
    $remoteSha=(& git rev-parse "origin/$branch").Trim()
    if ($LASTEXITCODE -ne 0 -or $remoteSha -ne $VerifiedSourceSha) { throw "origin/$branch does not match the verified source." }
    $runsJson = & gh run list --workflow android.yml --commit $VerifiedSourceSha --limit 20 --json databaseId,headSha,status,conclusion
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify exact-source CI. No signing attempted.' }
    $run = @($runsJson | ConvertFrom-Json) | Where-Object { $_.headSha -eq $VerifiedSourceSha -and $_.status -eq 'completed' -and $_.conclusion -eq 'success' } | Select-Object -First 1
    if (-not $run) { throw 'No successful complete CI run exists for this source SHA. No signing attempted.' }
    $detailsJson = & gh run view $run.databaseId --json headSha,jobs,url
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect required CI jobs.' }
    $details = $detailsJson | ConvertFrom-Json
    Assert-MemoraReleaseCi -Details $details -VerifiedSourceSha $VerifiedSourceSha
    $configuration = Get-Content app/build.gradle.kts -Raw
    $codes = [regex]::Matches($configuration, '(?m)^\s*versionCode\s*=\s*(\d+)\s*$')
    $names = [regex]::Matches($configuration, '(?m)^\s*versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$')
    if ($codes.Count -ne 1 -or $names.Count -ne 1) { throw 'Cannot determine the explicit release version from verified source.' }
    $versionCode = [int]$codes[0].Groups[1].Value
    $versionName = $names[0].Groups[1].Value
    if ($versionCode -lt 2) { throw 'Official Memora versionCode must be at least 2.' }
    $previousReleasePath = Join-Path (Split-Path $canonicalKey -Parent) 'memora-last-release.json'
    Assert-MemoraReleaseVersionAvailable -LedgerPath $previousReleasePath -VersionCode $versionCode -SourceSha $VerifiedSourceSha
    $destination=Get-MemoraReleaseDestination -QaCandidate:$QaCandidate -RepositoryPath $repo -OfficialLedgerPath $previousReleasePath -VersionName $versionName -OutputDirectory $OutputDirectory
    if ($QaCandidate) { Assert-MemoraReleaseVersionAvailable -LedgerPath $destination.LedgerPath -VersionCode $versionCode -SourceSha $VerifiedSourceSha }
    if ((Get-FileHash -LiteralPath $canonicalKey -Algorithm SHA256).Hash -ne $expectedKeyHash) { throw 'Canonical permanent keystore file hash mismatch.' }
    if ($env:RELEASE_KEYSTORE_PATH -and [IO.Path]::GetFullPath($env:RELEASE_KEYSTORE_PATH) -ne $canonicalKey) { throw 'RELEASE_KEYSTORE_PATH must identify the canonical Documents copy.' }
    if ($env:RELEASE_KEY_ALIAS -and $env:RELEASE_KEY_ALIAS -ne 'memora-release') { throw 'Release alias must be memora-release.' }
    if (-not $AndroidSdkRoot) { $AndroidSdkRoot = $env:ANDROID_SDK_ROOT }
    if (-not $AndroidSdkRoot -and (Test-Path (Join-Path $repo '.tools/android-sdk'))) { $AndroidSdkRoot = Join-Path $repo '.tools/android-sdk' }
    if (-not $AndroidSdkRoot) { $AndroidSdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $tools = Join-Path $AndroidSdkRoot 'build-tools\36.0.0'
    $apksigner = Join-Path $tools 'apksigner.bat'
    $aapt = Join-Path $tools 'aapt2.exe'
    $zipalign = Join-Path $tools 'zipalign.exe'
    if (-not (Test-Path -LiteralPath $apksigner) -or -not (Test-Path -LiteralPath $aapt) -or -not (Test-Path -LiteralPath $zipalign)) { throw 'Android SDK build-tools 36.0.0 are required. Set ANDROID_HOME or pass -AndroidSdkRoot.' }
    $env:ANDROID_HOME = $AndroidSdkRoot
    $env:RELEASE_KEYSTORE_PATH = $canonicalKey
    $env:RELEASE_KEY_ALIAS = 'memora-release'
    # Decrypt only after source, CI, SDK, version and permanent-key checks succeed.
    # Environment values live only for this build; no future interactive password prompt.
    if (Test-Path -LiteralPath $identity.CredentialPath) {
        $credential = Read-MemoraSigningCredential
    } elseif (-not [string]::IsNullOrWhiteSpace($env:RELEASE_STORE_PASSWORD)) {
        # Also supports the owner's already-open credential-loaded console.
        $credential = [pscustomobject]@{
            StorePassword=(ConvertTo-SecureString $env:RELEASE_STORE_PASSWORD -AsPlainText -Force)
            KeyPassword=(ConvertTo-SecureString $(if ([string]::IsNullOrWhiteSpace($env:RELEASE_KEY_PASSWORD)) { $env:RELEASE_STORE_PASSWORD } else { $env:RELEASE_KEY_PASSWORD }) -AsPlainText -Force)
        }
    } else { throw 'Local signing credentials are unavailable. Run Setup-MemoraSigning.ps1 once in the owner console; never send the password in chat.' }
    foreach ($entry in @(
        @{ Name='RELEASE_STORE_PASSWORD'; Secure=$credential.StorePassword },
        @{ Name='RELEASE_KEY_PASSWORD'; Secure=$credential.KeyPassword }
    )) {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($entry.Secure)
        try { [Environment]::SetEnvironmentVariable($entry.Name,[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer),'Process') }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
    if ([string]::IsNullOrWhiteSpace($env:RELEASE_STORE_PASSWORD) -or [string]::IsNullOrWhiteSpace($env:RELEASE_KEY_PASSWORD)) { throw 'Signing credentials are required; no official APK produced.' }
    # This short local path avoids Windows AF_UNIX selector failures in long sandbox temp paths.
    $socketDirectory = Join-Path $repo '.tools'
    New-Item -ItemType Directory -Path $socketDirectory -Force | Out-Null
    $env:JAVA_TOOL_OPTIONS = ($previousEnvironment['JAVA_TOOL_OPTIONS'] + ' "-Djdk.net.unixdomain.tmpdir=' + $socketDirectory + '"').Trim()
    Write-Host "Building verified source $VerifiedSourceSha with the existing permanent key."
    # Capture tool diagnostics in memory only: signing credentials never enter a log file.
    $build = Invoke-MemoraReleaseTool -Path .\gradlew.bat -Arguments @(':app:assembleRelease','-PmemoraPhoneApks=true','--no-daemon','--no-configuration-cache','--console=plain')
    $buildExit = $build.ExitCode
    $build = $null
    if ($buildExit -ne 0) { throw 'Official release build failed. No Desktop APK published. Check SDK/JDK setup or signing credentials locally; never enable debug logs/build scans with credentials.' }
    $headSha=(& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot recheck source after build; no APK copied.' }
    $branch=(& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot recheck branch after build; no APK copied.' }
    $status=@(& git -c core.quotepath=false status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot recheck status after build; no APK copied.' }
    Assert-MemoraReleaseCheckout -QaCandidate:$QaCandidate -Branch $branch -HeadSha $headSha -VerifiedSourceSha $VerifiedSourceSha -Status $status
    $mapping = Join-Path $repo 'app\build\outputs\mapping\release\mapping.txt'
    if (-not (Test-Path -LiteralPath $mapping) -or (Get-Item -LiteralPath $mapping).Length -eq 0) { throw 'Release R8 mapping is missing; optimization is not verified.' }
    $artifacts = @(); $inspections = @{}
    foreach ($variant in @('arm64','universal')) {
    $file = if ($variant -eq 'arm64') { 'app-arm64-v8a-release.apk' } else { 'app-universal-release.apk' }
    $apk = Join-Path $repo "app\build\outputs\apk\release\$file"
    $OutputPath = Join-Path $destination.Directory $(if ($variant -eq 'arm64') { $destination.Arm64Name } else { $destination.UniversalName })
    if (-not (Test-Path -LiteralPath $apk)) { throw "Signed $variant release APK is missing." }
    $signature = Invoke-MemoraReleaseTool -Path $apksigner -Arguments @('verify','--verbose','--print-certs',$apk)
    if ($signature.ExitCode -ne 0) { throw 'apksigner verification failed; no official APK published.' }
    $digests = @($signature.Output | Select-String 'certificate SHA-256 digest:' | ForEach-Object { ($_.Line -split 'digest:',2)[1].Trim().Replace(':','').ToUpperInvariant() })
    if ($digests.Count -ne 1 -or $digests[0] -ne $expectedCert) { throw 'APK signing certificate does not match the permanent Memora identity.' }
    $badgingResult = Invoke-MemoraReleaseTool -Path $aapt -Arguments @('dump','badging',$apk)
    $badging = $badgingResult.Output -join "`n"
    $expectedMetadata = "package: name='com\.personallibrary\.app' versionCode='$versionCode' versionName='" + [regex]::Escape($versionName) + "'"
    if ($badgingResult.ExitCode -ne 0 -or $badging -notmatch $expectedMetadata) { throw 'APK package/version metadata mismatch.' }
    $alignment = Invoke-MemoraReleaseTool -Path $zipalign -Arguments @('-c','-P','16','4',$apk)
    if ($alignment.ExitCode -ne 0) { throw 'APK zip alignment verification failed.' }
    $inspection = Get-MemoraApkContents $apk
    $abis = if ($variant -eq 'arm64') { @('arm64-v8a') } else { @('arm64-v8a','armeabi-v7a','x86','x86_64') }
    Assert-MemoraApkContents -Contents $inspection -ExpectedAbis $abis
    $inspections[$variant] = $inspection
    $apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
    $record = [ordered]@{
        schemaVersion=1
        abiVariant=$variant; nativeAbis=$inspection.nativeAbis; zipalign='verified'; arm64Elf16Kb='verified'
        sourceSha=$VerifiedSourceSha; ciUrl=$details.url; ciJobs='core/android/server/emulator/signing-workflow success'
        applicationId='com.personallibrary.app'; versionName=$versionName; versionCode=$versionCode
        certificateSha256=$expectedCert; apksigner='verified'; apkPath=$OutputPath
        apkSha256=$apkHash; apkBytes=(Get-Item -LiteralPath $apk).Length
        physicalDeviceAcceptance='pending'; utc=[DateTime]::UtcNow.ToString('o')
    }
    if ($QaCandidate) { $record.releaseChannel='qa-candidate' }
    $artifacts += @{ ApkPath=$apk; OutputPath=$OutputPath; Record=$record }
    }
    Assert-MemoraApkPreservation -Baseline $inspections.universal -Optimized $inspections.arm64
    New-Item -ItemType Directory -Path (Split-Path $destination.SizeAnalysisPath -Parent) -Force | Out-Null
    $inspections | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $destination.SizeAnalysisPath -Encoding utf8
    Publish-MemoraReleaseSet -Artifacts $artifacts -LedgerPath $destination.LedgerPath -ReportPath $destination.ReportPath
    $artifacts.Record | ConvertTo-Json -Depth 8 | Write-Host
} finally {
    try { Restore-MemoraReleaseEnvironment -PreviousEnvironment $previousEnvironment -Credential $credential }
    finally { Pop-Location }
}
