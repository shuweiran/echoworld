param(
  [string]$Output = (Join-Path $PSScriptRoot 'runtime')
)

$ErrorActionPreference = 'Stop'
$jlink = (Get-Command jlink -ErrorAction Stop).Source
if (Test-Path -LiteralPath $Output) {
  throw "Runtime directory already exists: $Output. Choose a different output path."
}

& $jlink `
  --add-modules java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.sql,java.xml,jdk.unsupported `
  --strip-debug `
  --no-header-files `
  --no-man-pages `
  --compress=zip-6 `
  --output $Output

& (Join-Path $Output 'bin/java.exe') --version
