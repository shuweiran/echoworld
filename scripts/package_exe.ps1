param(
    [string]$OutputDir = "C:\\Users\\shuweiran\\Documents\\Codex\\2026-08-05\\http-localhost-5173\\outputs\\EchoWorld"
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$FrontendRoot = Join-Path $ProjectRoot 'frontend'
$StaticRoot = Join-Path $ProjectRoot 'src\\main\\resources\\static'
$Jar = Join-Path $ProjectRoot 'target\\roleplay-engine-1.0.0-SNAPSHOT.jar'

Push-Location $FrontendRoot
npm run build
Pop-Location

Copy-Item -Path (Join-Path $FrontendRoot 'dist\\*') -Destination $StaticRoot -Recurse -Force

Push-Location $ProjectRoot
mvn -q -DskipTests package
Pop-Location

if (Test-Path -LiteralPath $OutputDir) {
    Remove-Item -LiteralPath $OutputDir -Recurse -Force
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

jpackage `
    --type app-image `
    --name RoleplayV4 `
    --input (Join-Path $ProjectRoot 'target') `
    --main-jar (Split-Path $Jar -Leaf) `
    --dest $OutputDir `
    --app-version 1.0.0 `
    --vendor 'Roleplay' `
    --description '角色扮演与剧本杀桌面应用'

Write-Host "Built: $(Join-Path $OutputDir 'RoleplayV4\\RoleplayV4.exe')"
