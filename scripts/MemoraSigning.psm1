Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-MemoraSigningIdentity {
    [pscustomobject]@{
        KeystorePath = 'C:\Users\Administrator\Documents\Memora-Signing\memora-release.p12'
        CredentialPath = 'C:\Users\Administrator\Documents\Memora-Signing\memora-signing.credential.xml'
        KeyAlias = 'memora-release'
        KeystoreSha256 = '908E801EE64FAD19978A8D30E3E5951C4A3955BF59154AEA32182F0EF1C2FAD8'
        CertificateSha256 = '98686D31BFE4370CC30024439B56D9E72DE71C081951D31F5D4FDD31D4EB0598'
    }
}

function Assert-MemoraWindows {
    if ($env:OS -ne 'Windows_NT') { throw 'Memora credentials require Windows current-user DPAPI. No portable/plaintext fallback is allowed.' }
}

function Save-MemoraSigningCredential {
    param(
        [Parameter(Mandatory)][Security.SecureString]$StorePassword,
        [Parameter(Mandatory)][Security.SecureString]$KeyPassword,
        [string]$CredentialPath = (Get-MemoraSigningIdentity).CredentialPath,
        [switch]$Replace
    )
    Assert-MemoraWindows
    if ($StorePassword.Length -eq 0 -or $KeyPassword.Length -eq 0) { throw 'Both signing passwords must be nonempty.' }
    if ((Test-Path -LiteralPath $CredentialPath) -and -not $Replace) { throw 'A local signing profile already exists. Use setup -Replace only to replace it deliberately.' }
    $identity = Get-MemoraSigningIdentity
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $record = [pscustomobject]@{
        SchemaVersion = 1; UserSid = $sid.Value
        KeystorePath = $identity.KeystorePath; KeyAlias = $identity.KeyAlias
        CertificateSha256 = $identity.CertificateSha256
        StorePassword = $StorePassword; KeyPassword = $KeyPassword
    }
    $temporary = $CredentialPath + '.' + [Guid]::NewGuid().ToString('N') + '.pending'
    try {
        New-Item -ItemType File -Path $temporary | Out-Null
        $acl = New-Object Security.AccessControl.FileSecurity
        $acl.SetOwner($sid)
        $acl.SetAccessRuleProtection($true,$false)
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow'))
        Set-Acl -LiteralPath $temporary -AclObject $acl
        # On Windows Export-Clixml protects SecureString fields using CURRENT-USER DPAPI.
        # No -Key, machine scope, plaintext serialization or permanent environment variable.
        Export-Clixml -InputObject $record -LiteralPath $temporary -Depth 3 -Force
        if (Test-Path -LiteralPath $CredentialPath) {
            if (-not $Replace) { throw 'Signing profile appeared during setup.' }
            [IO.File]::Replace($temporary,$CredentialPath,[System.Management.Automation.Language.NullString]::Value)
        } else { [IO.File]::Move($temporary,$CredentialPath) }
    } catch {
        throw 'Could not save the Windows DPAPI signing profile. Existing credentials were not intentionally removed.'
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Read-MemoraSigningCredential {
    param([string]$CredentialPath = (Get-MemoraSigningIdentity).CredentialPath)
    Assert-MemoraWindows
    if (-not (Test-Path -LiteralPath $CredentialPath -PathType Leaf)) { throw 'Local DPAPI signing profile is missing. Run scripts/Setup-MemoraSigning.ps1 once in the owner PowerShell session.' }
    $record = $null
    try {
        $identity = Get-MemoraSigningIdentity
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $acl = Get-Acl -LiteralPath $CredentialPath
        $rules = @($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
        if (-not $acl.AreAccessRulesProtected -or $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $sid -or $rules[0].AccessControlType -ne 'Allow') { throw 'Invalid credential ACL.' }
        $record = Import-Clixml -LiteralPath $CredentialPath
        if ($record.SchemaVersion -ne 1 -or $record.UserSid -ne $sid -or
            $record.KeystorePath -ne $identity.KeystorePath -or $record.KeyAlias -ne $identity.KeyAlias -or
            $record.CertificateSha256 -ne $identity.CertificateSha256 -or
            $record.StorePassword -isnot [Security.SecureString] -or $record.KeyPassword -isnot [Security.SecureString] -or
            $record.StorePassword.Length -eq 0 -or $record.KeyPassword.Length -eq 0) { throw 'Invalid credential identity or payload.' }
        return $record
    } catch {
        if ($record) {
            foreach ($name in 'StorePassword','KeyPassword') {
                $property = $record.PSObject.Properties[$name]
                if ($property -and $property.Value -is [Security.SecureString]) { $property.Value.Dispose() }
            }
        }
        throw 'Local signing profile cannot be decrypted or validated for this Windows user. Re-run owner-local setup -Replace; no signing attempted.'
    }
}

function Invoke-MemoraReleaseTool {
    param([Parameter(Mandatory)][string]$Path, [string[]]$Arguments)
    # Windows PowerShell 5.1 converts redirected native stderr into ErrorRecords.
    # Capture it without terminating on harmless stderr; the exit code is authoritative.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $Path @Arguments 2>&1
        $code = $LASTEXITCODE
        [pscustomobject]@{ ExitCode=$code; Output=$output }
    } finally { $ErrorActionPreference = $previousPreference }
}

function Restore-MemoraReleaseEnvironment {
    param([string]$PendingPath, [hashtable]$PreviousEnvironment, $Credential)
    try {
        if ($PendingPath -and (Test-Path -LiteralPath $PendingPath)) { Remove-Item -LiteralPath $PendingPath }
    } finally {
        try {
            foreach ($name in $PreviousEnvironment.Keys) {
                $value = $PreviousEnvironment[$name]
                if ($null -eq $value) { [Environment]::SetEnvironmentVariable($name,[System.Management.Automation.Language.NullString]::Value,'Process') }
                else { [Environment]::SetEnvironmentVariable($name,$value,'Process') }
            }
        }
        finally { if ($Credential) { $Credential.StorePassword.Dispose(); $Credential.KeyPassword.Dispose() } }
    }
}

function Assert-MemoraReleaseRecord {
    param($Record)
    try {
        $identity = Get-MemoraSigningIdentity
        if (-not $Record -or $Record.schemaVersion -ne 1 -or
            $Record.sourceSha -isnot [string] -or $Record.sourceSha -notmatch '^[0-9a-f]{40}$' -or
            ($Record.versionCode -isnot [int] -and $Record.versionCode -isnot [long]) -or $Record.versionCode -lt 2 -or $Record.versionCode -gt [int]::MaxValue -or
            $Record.versionName -isnot [string] -or $Record.versionName -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$' -or
            $Record.applicationId -ne 'com.personallibrary.app' -or $Record.certificateSha256 -ne $identity.CertificateSha256 -or
            $Record.apkSha256 -isnot [string] -or $Record.apkSha256 -notmatch '^[0-9A-Fa-f]{64}$' -or
            ($Record.apkBytes -isnot [int] -and $Record.apkBytes -isnot [long]) -or $Record.apkBytes -le 0) { throw 'Invalid provenance.' }
    } catch { throw 'Previous/new release record is invalid. No version reuse or publication is allowed.' }
}

function Assert-MemoraReleaseVersionAvailable {
    param([string]$LedgerPath, [int]$VersionCode, [string]$SourceSha)
    if (Test-Path -LiteralPath $LedgerPath) {
        try { $previous = Get-Content -LiteralPath $LedgerPath -Raw | ConvertFrom-Json }
        catch { throw 'Previous release record cannot be read. No version reuse is allowed.' }
        Assert-MemoraReleaseRecord $previous
        if ($VersionCode -lt $previous.versionCode -or ($VersionCode -eq $previous.versionCode -and $SourceSha -ne $previous.sourceSha)) { throw 'Increase versionCode before releasing changed source.' }
    }
}

function Save-MemoraReleaseRecord {
    param([string]$Path, $Record)
    Assert-MemoraReleaseRecord $Record
    $temporary = $Path + '.' + [Guid]::NewGuid().ToString('N') + '.pending'
    try {
        $Record | ConvertTo-Json | Set-Content -LiteralPath $temporary -Encoding utf8
        if (Test-Path -LiteralPath $Path) { [IO.File]::Replace($temporary,$Path,[System.Management.Automation.Language.NullString]::Value) }
        else { [IO.File]::Move($temporary,$Path) }
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

function Publish-MemoraReleaseArtifact {
    param([string]$ApkPath, [string]$OutputPath, [string]$LedgerPath, [string]$ReportPath, $Record)
    Assert-MemoraReleaseRecord $Record
    $pending = $OutputPath + '.' + [Guid]::NewGuid().ToString('N') + '.pending'
    try {
        Copy-Item -LiteralPath $ApkPath -Destination $pending
        if ((Get-FileHash -LiteralPath $pending -Algorithm SHA256).Hash -ne $Record.apkSha256 -or (Get-Item -LiteralPath $pending).Length -ne $Record.apkBytes) { throw 'Desktop staging checksum/size mismatch.' }
        # Commit provenance before exposing a new official APK. Same-SHA retries are safe.
        Save-MemoraReleaseRecord -Path $LedgerPath -Record $Record
        Move-Item -LiteralPath $pending -Destination $OutputPath -Force
        if (-not (Test-Path -LiteralPath $OutputPath) -or (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash -ne $Record.apkSha256) { throw 'Desktop final checksum verification failed.' }
        Save-MemoraReleaseRecord -Path $ReportPath -Record $Record
    } finally { if (Test-Path -LiteralPath $pending) { Remove-Item -LiteralPath $pending -Force } }
}

Export-ModuleMember -Function Get-MemoraSigningIdentity,Save-MemoraSigningCredential,Read-MemoraSigningCredential,Invoke-MemoraReleaseTool,Restore-MemoraReleaseEnvironment,Assert-MemoraReleaseVersionAvailable,Publish-MemoraReleaseArtifact
