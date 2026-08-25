param(
  [Parameter(Mandatory = $true)] [string]$EngineJar,
  [Parameter(Mandatory = $true)] [string]$UpdateUrl,
  [string]$RuntimeDir = '',
  [switch]$Force
)

$ErrorActionPreference = 'Stop'
$stage = Join-Path $PSScriptRoot 'staged'
if ([string]::IsNullOrWhiteSpace($RuntimeDir)) { $RuntimeDir = Join-Path $PSScriptRoot 'runtime-release' }
$enginePath = [System.IO.Path]::GetFullPath($EngineJar)
$runtimePath = [System.IO.Path]::GetFullPath($RuntimeDir)

if (-not (Test-Path -LiteralPath $enginePath -PathType Leaf)) { throw "Engine jar was not found: $enginePath" }
if (-not (Test-Path -LiteralPath $runtimePath -PathType Container)) { throw "Runtime directory was not found: $runtimePath" }
if ($UpdateUrl -notmatch '^https://') { throw 'UpdateUrl must use HTTPS.' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($enginePath)
try {
  $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
  if ($null -eq $entry) { throw 'The engine jar has no manifest.' }
  $reader = [System.IO.StreamReader]::new($entry.Open())
  $manifest = $reader.ReadToEnd()
  $reader.Dispose()
  if ($manifest -notmatch '(?m)^Main-Class:\s+org\.springframework\.boot\.loader') {
    throw 'The engine jar is not an executable Spring Boot jar. Run the backend package step first.'
  }
} finally { $archive.Dispose() }

if (Test-Path -LiteralPath $stage) {
  if (-not $Force) { throw "Release stage already exists: $stage. Review it or rerun with -Force." }
  Remove-Item -LiteralPath $stage -Recurse -Force
}
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item -LiteralPath $enginePath -Destination (Join-Path $stage 'roleplay-engine.jar')
@{ updateUrl = $UpdateUrl.TrimEnd('/') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'update-config.json') -Encoding utf8
Write-Output "Release stage prepared: $stage"
