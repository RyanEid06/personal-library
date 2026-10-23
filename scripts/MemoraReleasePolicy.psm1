Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-MemoraReleaseCheckout {
    param([switch]$QaCandidate,[string]$Branch,[string]$HeadSha,[string]$VerifiedSourceSha,[AllowEmptyCollection()][string[]]$Status)
    if ($VerifiedSourceSha -notmatch '^[0-9a-f]{40}$' -or $HeadSha -cne $VerifiedSourceSha) { throw 'HEAD does not match the verified source SHA.' }
    $expectedBranch=if ($QaCandidate) { 'codex/v1.0.1-final-qa' } else { 'main' }
    if ($Branch -cne $expectedBranch) { throw "Release mode requires the verified $expectedBranch checkout." }
    foreach ($entry in $Status) {
        # Full porcelain-v1 output must be requested with --untracked-files=all.
        # Only these owner-preserved, unrelated untracked files are exempted.
        if ($QaCandidate -and $entry -ceq '?? classes.jar') { continue }
        if ($QaCandidate -and $entry -cmatch '^\?\? docs/qa/(?:[^\\"\x00-\x1f]+)?$') {
            $path=$entry.Substring(3)
            if (@($path.Split('/') | Where-Object { $_ -eq '.' -or $_ -eq '..' }).Count -eq 0) { continue }
        }
        throw 'Working tree contains changes outside this release mode policy.'
    }
}

function Assert-MemoraReleaseCi {
    param($Details,[string]$VerifiedSourceSha)
    if ($VerifiedSourceSha -notmatch '^[0-9a-f]{40}$' -or $Details.headSha -cne $VerifiedSourceSha) { throw 'CI details do not match the verified source SHA. No signing attempted.' }
    foreach ($job in @('core','android','server','emulator','signing-workflow')) {
        $matching=@($Details.jobs | Where-Object { $_.name -ceq $job })
        if ($matching.Count -ne 1 -or $matching[0].status -ne 'completed' -or $matching[0].conclusion -ne 'success') { throw "Required CI job is not terminal-success: $job" }
    }
}

function Get-MemoraReleaseDestination {
    param([switch]$QaCandidate,[string]$RepositoryPath,[string]$OfficialLedgerPath,[string]$VersionName,[string]$OutputDirectory)
    if ($VersionName -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { throw 'Invalid release version name.' }
    if ($QaCandidate) {
        $candidate=Join-Path $RepositoryPath "artifacts/release-v$VersionName-candidate"
        return [pscustomobject]@{
            Directory=$candidate; LedgerPath=(Join-Path $candidate 'candidate-last-build.json')
            ReportPath=(Join-Path $candidate 'candidate-release.json'); SizeAnalysisPath=(Join-Path $candidate 'candidate-size-analysis.json')
            Arm64Name="Memora-v$VersionName-arm64-candidate.apk"; UniversalName="Memora-v$VersionName-universal-candidate.apk"
        }
    }
    $reports=Join-Path $RepositoryPath 'artifacts/release-v1'
    [pscustomobject]@{
        Directory=$OutputDirectory; LedgerPath=$OfficialLedgerPath
        ReportPath=(Join-Path $reports 'official-release.json'); SizeAnalysisPath=(Join-Path $reports 'official-size-analysis.json')
        Arm64Name="Memora-v$VersionName-arm64-official.apk"; UniversalName="Memora-v$VersionName-universal.apk"
    }
}

Export-ModuleMember -Function Assert-MemoraReleaseCheckout,Assert-MemoraReleaseCi,Get-MemoraReleaseDestination
