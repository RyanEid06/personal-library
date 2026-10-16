$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'MemoraSigning.psm1') -Force
$directory = Join-Path ([IO.Path]::GetTempPath()) ('memora-signing-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory | Out-Null
$path = Join-Path $directory 'credential.xml'
$first = 'synthetic-' + [Guid]::NewGuid().ToString('N')
$second = 'synthetic-' + [Guid]::NewGuid().ToString('N')
$store = ConvertTo-SecureString $first -AsPlainText -Force
$key = ConvertTo-SecureString $second -AsPlainText -Force
$passed = 0
function Assert-Condition($condition, $name) {
    if (-not $condition) { throw "Signing workflow test failed: $name" }
    $script:passed++
}
function Assert-Rejected([scriptblock]$operation, $name) {
    $rejected = $false
    try { & $operation | Out-Null } catch {
        if ($_.Exception.Message.Contains($first) -or $_.Exception.Message.Contains($second)) { throw 'Credential leaked in an error.' }
        $rejected = $true
    }
    Assert-Condition $rejected $name
}
function Read-Plain([Security.SecureString]$secret) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}
try {
    $native = Invoke-MemoraReleaseTool -Path cmd.exe -Arguments @('/c','echo synthetic-warning 1>&2 & exit /b 0')
    Assert-Condition ($native.ExitCode -eq 0 -and ($native.Output -join '') -like '*synthetic-warning*') 'native stderr does not hide success'
    $native = Invoke-MemoraReleaseTool -Path cmd.exe -Arguments @('/c','exit /b 7')
    Assert-Condition ($native.ExitCode -eq 7) 'native failure exit code retained'
    Assert-Rejected { Read-MemoraSigningCredential -CredentialPath $path } 'missing profile fails closed'
    Save-MemoraSigningCredential -StorePassword $store -KeyPassword $key -CredentialPath $path
    $loaded = Read-MemoraSigningCredential -CredentialPath $path
    try {
        Assert-Condition ((Read-Plain $loaded.StorePassword) -ceq $first -and (Read-Plain $loaded.KeyPassword) -ceq $second) 'DPAPI round trip'
    } finally { $loaded.StorePassword.Dispose(); $loaded.KeyPassword.Dispose() }
    $original = Get-Content -LiteralPath $path -Raw
    Assert-Condition (-not $original.Contains($first) -and -not $original.Contains($second)) 'no plaintext in profile'
    $acl = Get-Acl -LiteralPath $path
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $rules = @($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    Assert-Condition ($acl.AreAccessRulesProtected -and $rules.Count -eq 1 -and $rules[0].IdentityReference.Value -eq $sid) 'current-user-only file ACL'
    Assert-Rejected { Save-MemoraSigningCredential -StorePassword $key -KeyPassword $store -CredentialPath $path } 'overwrite requires Replace'
    $locked = [IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try { Assert-Rejected { Save-MemoraSigningCredential -StorePassword $key -KeyPassword $store -CredentialPath $path -Replace } 'failed replacement rejects safely' }
    finally { $locked.Dispose() }
    Assert-Condition ((Get-Content -LiteralPath $path -Raw) -ceq $original) 'failed replacement preserves existing credentials'
    foreach ($change in @(
        @{ From=$sid; To='S-1-5-21-0-0-0-9999'; Name='wrong Windows identity' },
        @{ From='memora-release'; To='wrong-alias'; Name='wrong signing identity' },
        @{ From='98686D31BFE4370CC30024439B56D9E72DE71C081951D31F5D4FDD31D4EB0598'; To=('0' * 64); Name='wrong certificate' }
    )) {
        $original.Replace($change.From,$change.To) | Set-Content -LiteralPath $path
        Assert-Rejected { Read-MemoraSigningCredential -CredentialPath $path } $change.Name
        $original | Set-Content -LiteralPath $path
    }
    [xml]$xml = $original
    $encrypted = $xml.SelectSingleNode("//*[local-name()='SS']")
    $encrypted.InnerText = '00' * 32
    $xml.Save($path)
    Assert-Rejected { Read-MemoraSigningCredential -CredentialPath $path } 'corrupt DPAPI payload'
    $original | Set-Content -LiteralPath $path
    Save-MemoraSigningCredential -StorePassword $key -KeyPassword $store -CredentialPath $path -Replace
    $loaded = Read-MemoraSigningCredential -CredentialPath $path
    try { Assert-Condition ((Read-Plain $loaded.StorePassword) -ceq $second) 'explicit atomic replacement' }
    finally { $loaded.StorePassword.Dispose(); $loaded.KeyPassword.Dispose() }
    $empty = New-Object Security.SecureString
    try { Assert-Rejected { Save-MemoraSigningCredential -StorePassword $empty -KeyPassword $key -CredentialPath (Join-Path $directory 'empty.xml') } 'empty password rejected' }
    finally { $empty.Dispose() }
    Write-Output "Memora DPAPI workflow: $passed tests passed. No production key or credential used."
} finally {
    $store.Dispose(); $key.Dispose()
    $resolved = [IO.Path]::GetFullPath($directory)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'memora-signing-test-*') { throw 'Test cleanup containment check failed.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
