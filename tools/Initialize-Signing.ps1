$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$signingDir = Join-Path $project '.signing'
$store = Join-Path $signingDir 'standalone.p12'
$properties = Join-Path $signingDir 'signing.properties'
if ((Test-Path -LiteralPath $store) -and (Test-Path -LiteralPath $properties)) {
    Write-Output 'Existing standalone signing identity retained.'
    exit 0
}
if ((Test-Path -LiteralPath $store) -or (Test-Path -LiteralPath $properties)) {
    throw 'Signing files are incomplete. Restore the existing pair before continuing.'
}
New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
$env:N5_STANDALONE_SIGNING_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
& keytool -genkeypair -alias standalone -keystore $store -storetype PKCS12 -storepass:env N5_STANDALONE_SIGNING_PASSWORD -keypass:env N5_STANDALONE_SIGNING_PASSWORD -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=LibreTV Standalone TV' -noprompt
if ($LASTEXITCODE -ne 0) { throw 'Could not initialize standalone signing identity.' }
[IO.File]::WriteAllText($properties, "storePassword=$env:N5_STANDALONE_SIGNING_PASSWORD`n", [Text.UTF8Encoding]::new($false))
Remove-Item Env:N5_STANDALONE_SIGNING_PASSWORD
Write-Output 'Standalone signing identity created under .signing; retain it for compatible future updates.'
