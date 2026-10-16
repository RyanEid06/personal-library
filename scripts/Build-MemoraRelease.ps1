param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$VerifiedSourceSha,
    [string]$AndroidSdkRoot = $env:ANDROID_HOME,
    [string]$OutputPath = 'C:\Users\Administrator\Desktop\Memora-v1.0.0-official.apk'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$canonicalKey = 'C:\Users\Administrator\Documents\Memora-Signing\memora-release.p12'
$expectedKeyHash = '908E801EE64FAD19978A8D30E3E5951C4A3955BF59154AEA32182F0EF1C2FAD8'
$expectedCert = '98686D31BFE4370CC30024439B56D9E72DE71C081951D31F5D4FDD31D4EB0598'
$environmentNames = @('RELEASE_KEYSTORE_PATH','RELEASE_STORE_PASSWORD','RELEASE_KEY_ALIAS','RELEASE_KEY_PASSWORD','ANDROID_HOME','JAVA_TOOL_OPTIONS')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$pending = $null
Push-Location $repo
try {
    if ((& git rev-parse HEAD).Trim() -ne $VerifiedSourceSha -or $LASTEXITCODE -ne 0) { throw 'HEAD does not match the verified source SHA.' }
    if ((& git branch --show-current).Trim() -ne 'main') { throw 'Official V1 must be built from the verified main checkout.' }
    if (@(& git status --porcelain).Count -ne 0) { throw 'Working tree must be clean before an official build.' }
    & git fetch origin
    if ($LASTEXITCODE -ne 0 -or (& git rev-parse origin/main).Trim() -ne $VerifiedSourceSha) { throw 'origin/main does not match the verified source.' }
    $runsJson = & gh run list --workflow android.yml --commit $VerifiedSourceSha --limit 20 --json databaseId,headSha,status,conclusion
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify exact-source CI. No signing attempted.' }
    $run = @($runsJson | ConvertFrom-Json) | Where-Object { $_.headSha -eq $VerifiedSourceSha -and $_.status -eq 'completed' -and $_.conclusion -eq 'success' } | Select-Object -First 1
    if (-not $run) { throw 'No successful complete CI run exists for this source SHA. No signing attempted.' }
    $detailsJson = & gh run view $run.databaseId --json headSha,jobs,url
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect required CI jobs.' }
    $details = $detailsJson | ConvertFrom-Json
    foreach ($job in @('core','android','server','emulator')) {
        if (-not @($details.jobs | Where-Object { $_.name -eq $job -and $_.conclusion -eq 'success' }).Count) { throw "Required CI job is not green: $job" }
    }
    if ((Get-FileHash -LiteralPath $canonicalKey -Algorithm SHA256).Hash -ne $expectedKeyHash) { throw 'Canonical permanent keystore file hash mismatch.' }
    if ($env:RELEASE_KEYSTORE_PATH -and [IO.Path]::GetFullPath($env:RELEASE_KEYSTORE_PATH) -ne $canonicalKey) { throw 'RELEASE_KEYSTORE_PATH must identify the canonical Documents copy.' }
    if ($env:RELEASE_KEY_ALIAS -and $env:RELEASE_KEY_ALIAS -ne 'memora-release') { throw 'Release alias must be memora-release.' }
    if (-not $AndroidSdkRoot) { $AndroidSdkRoot = $env:ANDROID_SDK_ROOT }
    if (-not $AndroidSdkRoot) { $AndroidSdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $tools = Join-Path $AndroidSdkRoot 'build-tools\36.0.0'
    $apksigner = Join-Path $tools 'apksigner.bat'
    $aapt = Join-Path $tools 'aapt2.exe'
    if (-not (Test-Path -LiteralPath $apksigner) -or -not (Test-Path -LiteralPath $aapt)) { throw 'Android SDK build-tools 36.0.0 are required. Set ANDROID_HOME or pass -AndroidSdkRoot.' }
    $env:ANDROID_HOME = $AndroidSdkRoot
    $env:RELEASE_KEYSTORE_PATH = $canonicalKey
    $env:RELEASE_KEY_ALIAS = 'memora-release'
    # Prompt only on the owner's local console. Never pass a password as an argument.
    if ([string]::IsNullOrWhiteSpace($env:RELEASE_STORE_PASSWORD)) {
        $secure = Read-Host 'Enter the permanent Memora keystore password (hidden; never send it in chat)' -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $env:RELEASE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
    }
    if ([string]::IsNullOrWhiteSpace($env:RELEASE_KEY_PASSWORD)) { $env:RELEASE_KEY_PASSWORD = $env:RELEASE_STORE_PASSWORD }
    if ([string]::IsNullOrWhiteSpace($env:RELEASE_STORE_PASSWORD) -or [string]::IsNullOrWhiteSpace($env:RELEASE_KEY_PASSWORD)) { throw 'Signing credentials are required; no official APK produced.' }
    # This short local path avoids Windows AF_UNIX selector failures in long sandbox temp paths.
    $socketDirectory = Join-Path $repo '.tools'
    New-Item -ItemType Directory -Path $socketDirectory -Force | Out-Null
    $env:JAVA_TOOL_OPTIONS = ($previousEnvironment['JAVA_TOOL_OPTIONS'] + ' "-Djdk.net.unixdomain.tmpdir=' + $socketDirectory + '"').Trim()
    Write-Host "Building verified source $VerifiedSourceSha with the existing permanent key."
    # Capture tool diagnostics in memory only: signing credentials never enter a log file.
    $buildOutput = & .\gradlew.bat :app:assembleRelease --no-daemon --no-configuration-cache --console=plain 2>&1
    $buildExit = $LASTEXITCODE
    $buildOutput = $null
    if ($buildExit -ne 0) { throw 'Official release build failed. No Desktop APK published. Check SDK/JDK setup or signing credentials locally; never enable debug logs/build scans with credentials.' }
    if ((& git rev-parse HEAD).Trim() -ne $VerifiedSourceSha -or @(& git status --porcelain).Count -ne 0) { throw 'Source changed during build; no official APK published.' }
    $apk = Join-Path $repo 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw 'Signed release APK is missing.' }
    $signature = & $apksigner verify --verbose --print-certs $apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'apksigner verification failed; no official APK published.' }
    $digests = @($signature | Select-String 'certificate SHA-256 digest:' | ForEach-Object { ($_.Line -split 'digest:',2)[1].Trim().Replace(':','').ToUpperInvariant() })
    if ($digests.Count -ne 1 -or $digests[0] -ne $expectedCert) { throw 'APK signing certificate does not match the permanent Memora identity.' }
    $badging = (& $aapt dump badging $apk 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='com.personallibrary.app' versionCode='2' versionName='1.0.0'") { throw 'APK package/version metadata mismatch.' }
    $apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
    $pending = $OutputPath + '.pending'
    Copy-Item -LiteralPath $apk -Destination $pending -Force
    if ((Get-FileHash -LiteralPath $pending -Algorithm SHA256).Hash -ne $apkHash) { throw 'Desktop staging copy checksum mismatch.' }
    Move-Item -LiteralPath $pending -Destination $OutputPath -Force
    $pending = $null
    if (-not (Test-Path -LiteralPath $OutputPath) -or (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash -ne $apkHash) { throw 'Desktop final checksum verification failed.' }
    $record = [ordered]@{
        sourceSha=$VerifiedSourceSha; ciUrl=$details.url; ciJobs='core/android/server/emulator success'
        applicationId='com.personallibrary.app'; versionName='1.0.0'; versionCode=2
        certificateSha256=$expectedCert; apksigner='verified'; apkPath=$OutputPath
        apkSha256=$apkHash; apkBytes=(Get-Item -LiteralPath $OutputPath).Length
        physicalDeviceAcceptance='pending'; utc=[DateTime]::UtcNow.ToString('o')
    }
    New-Item -ItemType Directory -Path artifacts/release-v1 -Force | Out-Null
    $record | ConvertTo-Json | Set-Content -LiteralPath artifacts/release-v1/official-release.json -Encoding utf8
    $record | ConvertTo-Json | Write-Host
} finally {
    if ($pending -and (Test-Path -LiteralPath $pending)) { Remove-Item -LiteralPath $pending }
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    Pop-Location
}
