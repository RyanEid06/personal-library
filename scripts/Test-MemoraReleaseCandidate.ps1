$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'MemoraSigning.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'MemoraReleasePolicy.psm1') -Force
$directory = Join-Path ([IO.Path]::GetTempPath()) ('memora-candidate-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory | Out-Null
$failures = @(); $passed = 0; $sha = 'a' * 40
function Require($condition) { if (-not $condition) { throw 'Condition failed.' } }
function Reject([scriptblock]$operation) {
    $rejected = $false
    try { & $operation } catch { $rejected = $true }
    Require $rejected
}
function Check([string]$name,[scriptblock]$test) {
    try { & $test; $script:passed++; Write-Output "PASS: $name" }
    catch { $script:failures += $name; Write-Output "FAILED: $name -- $($_.Exception.Message)" }
}
function New-CiDetails {
    [pscustomobject]@{
        headSha=$sha; url='https://example.invalid/synthetic-ci'
        jobs=@('core','android','server','emulator','signing-workflow' | ForEach-Object { [pscustomobject]@{ name=$_; status='completed'; conclusion='success' } })
    }
}
try {
    Check 'candidate permits only preserved untracked QA files' {
        Assert-MemoraReleaseCheckout -QaCandidate -Branch 'codex/v1.0.1-hardening' -HeadSha $sha -VerifiedSourceSha $sha -Status @('?? docs/qa/','?? docs/qa/a.md','?? docs/qa/nested/evidence.md')
        foreach ($status in @(' M app/build.gradle.kts','A  docs/qa/tracked.md',' M docs/qa/tracked.md','?? docs/qa-other/a.md','?? docs/qa/../other.md','?? other.md','?? "docs/qa/quoted.md"','?? docs/qa')) {
            Reject { Assert-MemoraReleaseCheckout -QaCandidate -Branch 'codex/v1.0.1-hardening' -HeadSha $sha -VerifiedSourceSha $sha -Status @($status) }
        }
    }
    Check 'candidate rejects wrong branch and changed source' {
        foreach ($branch in @('main','codex/another','')) {
            Reject { Assert-MemoraReleaseCheckout -QaCandidate -Branch $branch -HeadSha $sha -VerifiedSourceSha $sha -Status @() }
        }
        Reject { Assert-MemoraReleaseCheckout -QaCandidate -Branch 'codex/v1.0.1-hardening' -HeadSha ('b' * 40) -VerifiedSourceSha $sha -Status @() }
    }
    Check 'official checkout still requires clean main' {
        Assert-MemoraReleaseCheckout -Branch main -HeadSha $sha -VerifiedSourceSha $sha -Status @()
        Reject { Assert-MemoraReleaseCheckout -Branch main -HeadSha $sha -VerifiedSourceSha $sha -Status @('?? docs/qa/a.md') }
        Reject { Assert-MemoraReleaseCheckout -Branch 'codex/v1.0.1-hardening' -HeadSha $sha -VerifiedSourceSha $sha -Status @() }
    }
    Check 'CI guard requires exact SHA and five terminal-success jobs' {
        Assert-MemoraReleaseCi -Details (New-CiDetails) -VerifiedSourceSha $sha
        $details=New-CiDetails; $details.headSha='b' * 40
        Reject { Assert-MemoraReleaseCi -Details $details -VerifiedSourceSha $sha }
        foreach ($job in @('core','android','server','emulator','signing-workflow')) {
            $details=New-CiDetails; $details.jobs=@($details.jobs | Where-Object { $_.name -ne $job })
            Reject { Assert-MemoraReleaseCi -Details $details -VerifiedSourceSha $sha }
            foreach ($conclusion in @('failure','skipped','cancelled','')) {
                $details=New-CiDetails; ($details.jobs | Where-Object { $_.name -eq $job }).conclusion=$conclusion
                Reject { Assert-MemoraReleaseCi -Details $details -VerifiedSourceSha $sha }
            }
            $details=New-CiDetails; ($details.jobs | Where-Object { $_.name -eq $job }).status='in_progress'
            Reject { Assert-MemoraReleaseCi -Details $details -VerifiedSourceSha $sha }
        }
        $details=New-CiDetails; $details.jobs+=@($details.jobs[0])
        Reject { Assert-MemoraReleaseCi -Details $details -VerifiedSourceSha $sha }
        Reject { Assert-MemoraReleaseCi -Details ([pscustomobject]@{ headSha=$sha }) -VerifiedSourceSha $sha }
    }
    Check 'entry script rejects candidate without explicit SHA or with output override before Git/signing' {
        $entry=Join-Path $PSScriptRoot 'Build-MemoraRelease.ps1'
        # Both cases stop before checkout/network/key access, even in a dirty checkout.
        foreach ($parameters in @(@{ QaCandidate=$true },@{ QaCandidate=$true; VerifiedSourceSha=$sha; OutputDirectory=$directory })) {
            $message=''
            try { & $entry @parameters } catch { $message=$_.Exception.Message }
            Require ($message -like 'QA candidate mode requires -VerifiedSourceSha*')
        }
    }
    $officialLedger = Join-Path $directory 'Documents/last-release.json'
    $officialReports = Join-Path $directory 'repo/artifacts/release-v1'
    New-Item -ItemType Directory -Path (Split-Path $officialLedger -Parent),$officialReports -Force | Out-Null
    $identity=Get-MemoraSigningIdentity
    $previous=@{ schemaVersion=1; sourceSha=('b' * 40); versionCode=2; versionName='1.0.0'; applicationId='com.personallibrary.app'; certificateSha256=$identity.CertificateSha256; apkSha256=('B' * 64); apkBytes=1 }
    $previous | ConvertTo-Json | Set-Content -LiteralPath $officialLedger
    'official report sentinel' | Set-Content (Join-Path $officialReports 'official-release.json')
    'official size sentinel' | Set-Content (Join-Path $officialReports 'official-size-analysis.json')
    $officialSnapshot=@{}
    foreach ($path in @($officialLedger,(Join-Path $officialReports 'official-release.json'),(Join-Path $officialReports 'official-size-analysis.json'))) { $officialSnapshot[$path]=(Get-FileHash $path).Hash }
    $repo=Join-Path $directory 'repo'
    $plan=Get-MemoraReleaseDestination -QaCandidate -RepositoryPath $repo -OfficialLedgerPath $officialLedger -VersionName '1.0.1' -OutputDirectory (Join-Path $directory 'Desktop')
    Check 'candidate paths and names are isolated while official destinations stay stable' {
        Require ($plan.Directory -eq (Join-Path $repo 'artifacts/release-v1.0.1-candidate'))
        Require ($plan.LedgerPath -ne $officialLedger -and (Split-Path $plan.ReportPath -Parent) -ne $officialReports)
        Require ($plan.Arm64Name -eq 'Memora-v1.0.1-arm64-candidate.apk' -and $plan.UniversalName -eq 'Memora-v1.0.1-universal-candidate.apk')
        $official=Get-MemoraReleaseDestination -RepositoryPath $repo -OfficialLedgerPath $officialLedger -VersionName '1.0.1' -OutputDirectory (Join-Path $directory 'Desktop')
        Require ($official.LedgerPath -eq $officialLedger -and $official.ReportPath -eq (Join-Path $officialReports 'official-release.json'))
        Require ($official.Arm64Name -eq 'Memora-v1.0.1-arm64-official.apk' -and $official.UniversalName -eq 'Memora-v1.0.1-universal.apk')
        Reject { Get-MemoraReleaseDestination -QaCandidate -RepositoryPath $repo -OfficialLedgerPath $officialLedger -VersionName '../escape' }
    }
    Check 'candidate still enforces official rollback and reuse guard' {
        Reject { Assert-MemoraReleaseVersionAvailable -LedgerPath $officialLedger -VersionCode 2 -SourceSha $sha }
        Reject { Assert-MemoraReleaseVersionAvailable -LedgerPath $officialLedger -VersionCode 1 -SourceSha $sha }
        Assert-MemoraReleaseVersionAvailable -LedgerPath $officialLedger -VersionCode 3 -SourceSha $sha
    }
    Check 'actual candidate publication leaves every official ledger/report byte unchanged' {
        New-Item -ItemType Directory -Path $plan.Directory -Force | Out-Null
        $pair=@()
        foreach ($variant in @('arm64','universal')) {
            $apk=Join-Path $directory "$variant.apk"
            "synthetic $variant artifact; no signing" | Set-Content -LiteralPath $apk
            $name=if ($variant -eq 'arm64') { $plan.Arm64Name } else { $plan.UniversalName }
            $record=$previous.Clone(); $record.sourceSha=$sha; $record.versionCode=3; $record.versionName='1.0.1'; $record.abiVariant=$variant; $record.releaseChannel='qa-candidate'
            $record.apkSha256=(Get-FileHash $apk).Hash; $record.apkBytes=(Get-Item $apk).Length
            $pair+=@{ ApkPath=$apk; OutputPath=(Join-Path $plan.Directory $name); Record=$record }
        }
        @{ synthetic='size inspection' } | ConvertTo-Json | Set-Content -LiteralPath $plan.SizeAnalysisPath
        Publish-MemoraReleaseSet -Artifacts $pair -LedgerPath $plan.LedgerPath -ReportPath $plan.ReportPath
        foreach ($artifact in $pair) { Require ((Get-FileHash $artifact.OutputPath).Hash -eq $artifact.Record.apkSha256) }
        $report=Get-Content $plan.ReportPath -Raw | ConvertFrom-Json
        Require ($report.releaseChannel -eq 'qa-candidate' -and $report.artifacts.Count -eq 2)
        Assert-MemoraReleaseVersionAvailable -LedgerPath $plan.LedgerPath -VersionCode 3 -SourceSha $sha
        Reject { Assert-MemoraReleaseVersionAvailable -LedgerPath $plan.LedgerPath -VersionCode 3 -SourceSha ('c' * 40) }
        foreach ($path in $officialSnapshot.Keys) { Require ((Get-FileHash $path).Hash -eq $officialSnapshot[$path]) }
    }
    if ($failures.Count) { throw "$($failures.Count) candidate release regressions failed." }
    Write-Output "Memora candidate release safety: $passed tests passed. Synthetic files only; no production credentials, signing, Git or network used."
} finally {
    $resolved=[IO.Path]::GetFullPath($directory)
    $temporaryRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($temporaryRoot,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'memora-candidate-test-*') { throw 'Test cleanup containment failed.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
