$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'MemoraSigning.psm1') -Force
$directory = Join-Path ([IO.Path]::GetTempPath()) ('memora-release-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory | Out-Null
$failures = @()
$passed = 0
function Check([string]$name,[scriptblock]$test) {
    try { & $test; $script:passed++ }
    catch { $script:failures += $name; Write-Output "FAILED: $name" }
}
function Require($condition) { if (-not $condition) { throw 'Condition failed.' } }
function Reject([scriptblock]$operation) {
    $failed = $false
    try { & $operation } catch { $failed = $true }
    Require $failed
}
try {
    Check 'locked staging cleanup still restores environment and disposes credentials' {
        $pending = Join-Path $directory 'locked.pending'
        'synthetic artifact' | Set-Content -LiteralPath $pending
        $credential = [pscustomobject]@{ StorePassword=(ConvertTo-SecureString 'synthetic' -AsPlainText -Force); KeyPassword=(ConvertTo-SecureString 'synthetic' -AsPlainText -Force) }
        $previous = [Environment]::GetEnvironmentVariable('MEMORA_TEST_TRANSIENT','Process')
        $env:MEMORA_TEST_TRANSIENT = 'synthetic-transient'
        $locked = [IO.File]::Open($pending,'Open','Read','None')
        try {
            Reject { Restore-MemoraReleaseEnvironment -PendingPath $pending -PreviousEnvironment @{MEMORA_TEST_TRANSIENT=$previous} -Credential $credential }
            Require ([Environment]::GetEnvironmentVariable('MEMORA_TEST_TRANSIENT','Process') -ceq $previous)
            Reject { $credential.StorePassword.Copy() }
            Reject { $credential.KeyPassword.Copy() }
        } finally {
            $locked.Dispose(); $credential.StorePassword.Dispose(); $credential.KeyPassword.Dispose()
            if ($null -eq $previous) { [Environment]::SetEnvironmentVariable('MEMORA_TEST_TRANSIENT',[System.Management.Automation.Language.NullString]::Value,'Process') }
            else { [Environment]::SetEnvironmentVariable('MEMORA_TEST_TRANSIENT',$previous,'Process') }
        }
    }
    Check 'empty existing release ledger fails closed' {
        $ledger = Join-Path $directory 'invalid.json'
        '{}' | Set-Content -LiteralPath $ledger
        Reject { Assert-MemoraReleaseVersionAvailable -LedgerPath $ledger -VersionCode 2 -SourceSha ('a' * 40) }
    }
    Check 'ledger write failure prevents Desktop publication and preserves old record' {
        $apk = Join-Path $directory 'synthetic.apk'
        $output = Join-Path $directory 'official.apk'
        $ledger = Join-Path $directory 'previous.json'
        $report = Join-Path $directory 'report.json'
        'synthetic artifact; no key/signature' | Set-Content -LiteralPath $apk
        '{"old":"preserve"}' | Set-Content -LiteralPath $ledger
        $old = Get-Content -LiteralPath $ledger -Raw
        $record = @{ schemaVersion=1; sourceSha=('a' * 40); versionCode=2; versionName='1.0.0'; applicationId='com.personallibrary.app'; certificateSha256=(Get-MemoraSigningIdentity).CertificateSha256; apkSha256=(Get-FileHash $apk -Algorithm SHA256).Hash; apkBytes=(Get-Item $apk).Length }
        $locked = [IO.File]::Open($ledger,'Open','Read','Read')
        try { Reject { Publish-MemoraReleaseArtifact -ApkPath $apk -OutputPath $output -LedgerPath $ledger -ReportPath $report -Record $record } }
        finally { $locked.Dispose() }
        Require (-not (Test-Path -LiteralPath $output))
        Require ((Get-Content -LiteralPath $ledger -Raw) -ceq $old)
    }
    foreach ($invalid in @('null','[]','{"schemaVersion":1,"versionCode":"2"}')) {
        Check "invalid ledger rejected: $invalid" {
            $ledger = Join-Path $directory 'invalid.json'
            $invalid | Set-Content -LiteralPath $ledger
            Reject { Assert-MemoraReleaseVersionAvailable -LedgerPath $ledger -VersionCode 2 -SourceSha ('a' * 40) }
        }
    }
    $validLedger = Join-Path $directory 'valid.json'
    $valid = @{ schemaVersion=1; sourceSha=('a' * 40); versionCode=2; versionName='1.0.0'; applicationId='com.personallibrary.app'; certificateSha256=(Get-MemoraSigningIdentity).CertificateSha256; apkSha256=('B' * 64); apkBytes=1 }
    $valid | ConvertTo-Json | Set-Content -LiteralPath $validLedger
    Check 'same-source same-code retry allowed' { Assert-MemoraReleaseVersionAvailable -LedgerPath $validLedger -VersionCode 2 -SourceSha ('a' * 40) }
    Check 'changed source cannot reuse versionCode' { Reject { Assert-MemoraReleaseVersionAvailable -LedgerPath $validLedger -VersionCode 2 -SourceSha ('b' * 40) } }
    Check 'version rollback rejected' { Reject { Assert-MemoraReleaseVersionAvailable -LedgerPath $validLedger -VersionCode 1 -SourceSha ('b' * 40) } }
    Check 'future higher versionCode allowed' { Assert-MemoraReleaseVersionAvailable -LedgerPath $validLedger -VersionCode 3 -SourceSha ('b' * 40) }
    Check 'checksum mismatch cannot publish or create a ledger' {
        $apk = Join-Path $directory 'synthetic.apk'
        $output = Join-Path $directory 'wrong-hash.apk'
        $ledger = Join-Path $directory 'wrong-hash.json'
        Reject { Publish-MemoraReleaseArtifact -ApkPath $apk -OutputPath $output -LedgerPath $ledger -ReportPath (Join-Path $directory 'wrong-hash-report.json') -Record $valid }
        Require (-not (Test-Path $output) -and -not (Test-Path $ledger))
    }
    Check 'successful publication has matching Desktop and durable public records' {
        $apk = Join-Path $directory 'synthetic.apk'
        $output = Join-Path $directory 'success.apk'
        $ledger = Join-Path $directory 'success-ledger.json'
        $report = Join-Path $directory 'success-report.json'
        $valid.apkSha256 = (Get-FileHash $apk -Algorithm SHA256).Hash
        $valid.apkBytes = (Get-Item $apk).Length
        Publish-MemoraReleaseArtifact -ApkPath $apk -OutputPath $output -LedgerPath $ledger -ReportPath $report -Record $valid
        Require ((Get-FileHash $output -Algorithm SHA256).Hash -eq $valid.apkSha256)
        Require ((Get-Content $ledger -Raw | ConvertFrom-Json).sourceSha -eq $valid.sourceSha)
        Require ((Get-Content $report -Raw | ConvertFrom-Json).apkSha256 -eq $valid.apkSha256)
        Require (@(Get-ChildItem -LiteralPath $directory -Filter 'success*.pending').Count -eq 0)
    }
    Check 'both artifacts are staged before publication and recorded together' {
        $first = Join-Path $directory 'pair-arm.apk'; $second = Join-Path $directory 'pair-universal.apk'
        'synthetic ARM64' | Set-Content $first; 'synthetic universal' | Set-Content $second
        $pair = @()
        foreach ($variant in @('arm64','universal')) {
            $source = if ($variant -eq 'arm64') { $first } else { $second }
            $record = $valid.Clone(); $record.abiVariant=$variant
            $record.apkSha256=(Get-FileHash $source -Algorithm SHA256).Hash; $record.apkBytes=(Get-Item $source).Length
            $pair += @{ ApkPath=$source; OutputPath=(Join-Path $directory "pair-$variant-official.apk"); Record=$record }
        }
        Publish-MemoraReleaseSet -Artifacts $pair -LedgerPath (Join-Path $directory 'pair-ledger.json') -ReportPath (Join-Path $directory 'pair-report.json')
        $report = Get-Content (Join-Path $directory 'pair-report.json') -Raw | ConvertFrom-Json
        Require ($report.artifacts.Count -eq 2)
        foreach ($artifact in $pair) { Require ((Get-FileHash $artifact.OutputPath -Algorithm SHA256).Hash -eq $artifact.Record.apkSha256) }
        $pair[1].Record.apkSha256='0' * 64
        $pair[0].OutputPath=Join-Path $directory 'rejected-arm.apk'; $pair[1].OutputPath=Join-Path $directory 'rejected-universal.apk'
        Reject { Publish-MemoraReleaseSet -Artifacts $pair -LedgerPath (Join-Path $directory 'rejected-ledger.json') -ReportPath (Join-Path $directory 'rejected-report.json') }
        Require (-not (Test-Path $pair[0].OutputPath) -and -not (Test-Path (Join-Path $directory 'rejected-ledger.json')))
        $script:publicationPair=$pair
    }
    Check 'mixed source artifact pair cannot be published' {
        $script:publicationPair[1].Record.sourceSha='b' * 40
        Reject { Publish-MemoraReleaseSet -Artifacts $script:publicationPair -LedgerPath (Join-Path $directory 'mixed-ledger.json') -ReportPath (Join-Path $directory 'mixed-report.json') }
        Require (-not (Test-Path (Join-Path $directory 'mixed-ledger.json')))
    }
    foreach ($corruption in @('bad-hash','mixed-source','missing-artifact','wrong-variant','wrong-primary')) {
        Check "corrupt previous pair ledger fails closed: $corruption" {
            $record=Get-Content (Join-Path $directory 'pair-ledger.json') -Raw | ConvertFrom-Json
            switch ($corruption) {
                'bad-hash' { $record.artifacts[1].apkSha256='invalid' }
                'mixed-source' { $record.artifacts[1].sourceSha='b' * 40 }
                'missing-artifact' { $record.artifacts=@($record.artifacts[0]) }
                'wrong-variant' { $record.artifacts[1].abiVariant='arm64' }
                'wrong-primary' { $record.apkSha256='0' * 64 }
            }
            $ledger=Join-Path $directory 'corrupt-pair.json'
            $record | ConvertTo-Json -Depth 8 | Set-Content $ledger
            Reject { Assert-MemoraReleaseVersionAvailable -LedgerPath $ledger -VersionCode 2 -SourceSha ('a' * 40) }
        }
    }
    if ($failures.Count) { throw "$($failures.Count) release safety regressions failed." }
    Write-Output "Memora release safety: $passed tests passed. No production key or signing used."
} finally {
    $resolved = [IO.Path]::GetFullPath($directory)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'memora-release-test-*') { throw 'Test cleanup containment failed.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
