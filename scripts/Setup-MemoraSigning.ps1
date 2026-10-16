param([switch]$Replace)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'MemoraSigning.psm1') -Force
$identity = Get-MemoraSigningIdentity
if ($env:OS -ne 'Windows_NT') { throw 'Setup requires the owner Windows account and current-user DPAPI.' }
if ((Test-Path -LiteralPath $identity.CredentialPath) -and -not $Replace) { throw 'Local DPAPI profile already exists. Setup is needed only once; use -Replace for an intentional credential reset.' }
if ((Get-FileHash -LiteralPath $identity.KeystorePath -Algorithm SHA256).Hash -ne $identity.KeystoreSha256) { throw 'Canonical permanent keystore file hash mismatch.' }
if ($env:RELEASE_KEYSTORE_PATH -and [IO.Path]::GetFullPath($env:RELEASE_KEYSTORE_PATH) -ne $identity.KeystorePath) { throw 'Use the canonical Documents keystore.' }
if ($env:RELEASE_KEY_ALIAS -and $env:RELEASE_KEY_ALIAS -ne $identity.KeyAlias) { throw 'Alias must be memora-release.' }
$store = $null
$key = $null
$certificate = $null
try {
    # Existing owner-session variables are consumed without being displayed or persisted.
    $store = if ([string]::IsNullOrWhiteSpace($env:RELEASE_STORE_PASSWORD)) {
        Read-Host 'One-time Memora keystore password (hidden; never send in chat)' -AsSecureString
    } else { ConvertTo-SecureString $env:RELEASE_STORE_PASSWORD -AsPlainText -Force }
    $key = if ([string]::IsNullOrWhiteSpace($env:RELEASE_KEY_PASSWORD)) { $store.Copy() }
        else { ConvertTo-SecureString $env:RELEASE_KEY_PASSWORD -AsPlainText -Force }
    try {
        $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new(
            $identity.KeystorePath, $store, [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $fingerprint = ([BitConverter]::ToString($sha.ComputeHash($certificate.RawData))).Replace('-','') }
        finally { $sha.Dispose() }
        if (-not $certificate.HasPrivateKey -or $fingerprint -ne $identity.CertificateSha256 -or
            [DateTime]::Now -lt $certificate.NotBefore -or [DateTime]::Now -gt $certificate.NotAfter) { throw 'Certificate mismatch.' }
    } catch { throw 'Existing permanent keystore/password validation failed. No profile saved and no key created.' }
    Save-MemoraSigningCredential -StorePassword $store -KeyPassword $key -Replace:$Replace
    # Verify this account can decrypt the saved profile before reporting setup complete.
    $check = Read-MemoraSigningCredential
    $check.StorePassword.Dispose(); $check.KeyPassword.Dispose()
    Remove-Item Env:RELEASE_STORE_PASSWORD,Env:RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    Write-Host "One-time current-user DPAPI setup complete: $($identity.CredentialPath)"
    Write-Host 'Future builds use scripts/Build-MemoraRelease.ps1 without retyping the password.'
} finally {
    if ($certificate) { $certificate.Dispose() }
    if ($store) { $store.Dispose() }
    if ($key) { $key.Dispose() }
}
