param(
  [Parameter(Mandatory = $true)] [string]$BaseUrl,
  [string]$ReleaseDir = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ReleaseDir)) { $ReleaseDir = Join-Path $PSScriptRoot '..\release' }
$package = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\package.json') -Raw | ConvertFrom-Json
$version = $package.version
if ($version -eq '0.0.0') { throw 'Refusing to create an update manifest for version 0.0.0.' }
if ($BaseUrl -notmatch '^https://') { throw 'BaseUrl must use HTTPS.' }
$installer = Get-ChildItem -LiteralPath $ReleaseDir -File -Filter 'HuanjingBook-*-x64.exe' | Where-Object { $_.Name -notlike '*uninstaller*' } | Select-Object -First 1
if ($null -eq $installer) { throw 'Installer was not found in the release directory.' }
$bytes = [System.IO.File]::ReadAllBytes($installer.FullName)
$sha512Algorithm = [System.Security.Cryptography.SHA512]::Create()
try {
  $sha512 = [Convert]::ToBase64String($sha512Algorithm.ComputeHash($bytes))
} finally {
  $sha512Algorithm.Dispose()
}
$url = $installer.Name
$releaseDate = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
$manifest = @"
version: $version
files:
  - url: $url
    sha512: $sha512
    size: $($installer.Length)
path: $url
sha512: $sha512
releaseDate: '$releaseDate'
"@
Set-Content -LiteralPath (Join-Path $ReleaseDir 'latest.yml') -Value $manifest -Encoding utf8
Write-Output "Update manifest created: $(Join-Path $ReleaseDir 'latest.yml')"
