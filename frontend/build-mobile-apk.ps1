$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$localRoot = Join-Path $repoRoot '..\.local\ai-collaboration'
$gradleHome = Join-Path $localRoot 'gradle-home'
$gradleTmp = Join-Path $localRoot 'gradle-tmp'
$udsTmp = Join-Path $localRoot 'jdk-uds'
New-Item -ItemType Directory -Force $gradleHome, $gradleTmp, $udsTmp | Out-Null

$env:GRADLE_USER_HOME = $gradleHome
$env:GRADLE_OPTS = "-Djava.io.tmpdir=$($gradleTmp.Replace('\','/')) -Djdk.net.unixdomain.tmpdir=$($udsTmp.Replace('\','/')) -Djava.net.preferIPv4Stack=true"
$env:JAVA_TOOL_OPTIONS = $env:GRADLE_OPTS

Push-Location $repoRoot
try {
  npm run mobile:sync
  Push-Location (Join-Path $repoRoot 'android')
  try { & .\gradlew.bat assembleDebug --no-daemon }
  finally { Pop-Location }
}
finally { Pop-Location }
