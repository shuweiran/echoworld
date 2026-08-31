$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$failures = [System.Collections.Generic.List[string]]::new()

$required = @(
    'Packages/manifest.json',
    'ProjectSettings/ProjectVersion.txt',
    'ProjectSettings/ProjectSettings.asset',
    'Assets/EchoWorld/Runtime/EchoWorld.Client.asmdef',
    'Assets/EchoWorld/Runtime/Replica/WorldReplica.cs',
    'Assets/EchoWorld/Runtime/Assets/AddressablesAssetResolver.cs',
    'Assets/EchoWorld/Runtime/Commands/WorldReplicationCommandSender.cs',
    'Assets/EchoWorld/Runtime/Presentation/TransformPresentationAdapter.cs',
    'Assets/EchoWorld/Tests/EditMode/EchoWorld.Client.EditModeTests.asmdef'
)

foreach ($relativePath in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $relativePath))) {
        $failures.Add("Missing required file: $relativePath")
    }
}

try {
    $manifest = Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'Packages/manifest.json') -Encoding UTF8 | ConvertFrom-Json
    foreach ($package in @('com.unity.addressables', 'com.unity.ai.navigation', 'com.unity.nuget.newtonsoft-json', 'com.unity.test-framework')) {
        if (-not $manifest.dependencies.PSObject.Properties.Name.Contains($package)) {
            $failures.Add("Missing required package: $package")
        }
    }
} catch {
    $failures.Add("Packages/manifest.json is invalid JSON: $($_.Exception.Message)")
}

$forbiddenDirectories = @('Library', 'Temp', 'Obj', 'Build', 'Builds', 'Logs', 'UserSettings')
foreach ($name in $forbiddenDirectories) {
    $tracked = @(& git -C $projectRoot ls-files -- $name 2>$null)
    if ($LASTEXITCODE -ne 0) {
        $failures.Add("Unable to verify Git tracking state for generated Unity directory: $name")
    } elseif ($tracked.Count -gt 0) {
        $failures.Add("Generated Unity directory contains tracked files: $name")
    }
}

$binaryExtensions = @('.dll', '.exe', '.pdb', '.mdb', '.so', '.dylib', '.fbx', '.blend', '.psd')
$trackedFiles = @(& git -C $projectRoot ls-files -- . 2>$null)
if ($LASTEXITCODE -ne 0) {
    $failures.Add('Unable to list tracked Unity project files.')
    $trackedFiles = @()
}
$trackedBinaries = $trackedFiles | Where-Object {
    $binaryExtensions -contains [System.IO.Path]::GetExtension($_).ToLowerInvariant()
}
foreach ($binary in $trackedBinaries) {
    $failures.Add("Tracked binary/private asset is outside this skeleton's scope: $binary")
}

$sourceChecks = @(
    @{ Path = 'Assets/EchoWorld/Runtime/Protocol/ProtocolConstants.cs'; Pattern = 'CurrentVersion = 1' },
    @{ Path = 'Assets/EchoWorld/Runtime/Bootstrap/EchoWorldBootstrap.cs'; Pattern = '/ws/world' },
    @{ Path = 'Assets/EchoWorld/Runtime/Protocol/ProtocolDtos.cs'; Pattern = 'serverTimeEpochMillis' },
    @{ Path = 'Assets/EchoWorld/Runtime/Protocol/ProtocolDtos.cs'; Pattern = 'ReplicationCreateDto' },
    @{ Path = 'Assets/EchoWorld/Runtime/Protocol/ProtocolDtos.cs'; Pattern = 'ReplicationRemoveDto' },
    @{ Path = 'Assets/EchoWorld/Runtime/Replica/WorldReplica.cs'; Pattern = 'RequiresReplay' },
    @{ Path = 'Assets/EchoWorld/Runtime/Assets/AddressablesAssetResolver.cs'; Pattern = 'assetId' },
    @{ Path = 'Assets/EchoWorld/Runtime/Commands/WorldReplicationCommandSender.cs'; Pattern = 'ProtocolConstants.Hello' },
    @{ Path = 'Assets/EchoWorld/Runtime/Commands/WorldReplicationCommandSender.cs'; Pattern = 'ProtocolConstants.Interest' },
    @{ Path = 'Assets/EchoWorld/Runtime/Commands/WorldReplicationCommandSender.cs'; Pattern = 'ProtocolConstants.Ack' },
    @{ Path = 'Assets/EchoWorld/Runtime/Commands/WorldReplicationCommandSender.cs'; Pattern = 'ProtocolConstants.Replay' },
    @{ Path = 'Assets/EchoWorld/Runtime/Presentation/LocomotionPresenter.cs'; Pattern = 'applyRootMotion = false' }
)
foreach ($check in $sourceChecks) {
    $path = Join-Path $projectRoot $check.Path
    if ((Test-Path -LiteralPath $path) -and -not (Select-String -LiteralPath $path -SimpleMatch $check.Pattern -Quiet)) {
        $failures.Add("Static contract marker '$($check.Pattern)' missing from $($check.Path)")
    }
}

$commandFiles = @((Join-Path $projectRoot 'Assets/EchoWorld/Runtime/Commands/WorldReplicationCommandSender.cs'))
foreach ($commandFile in $commandFiles) {
    if (Select-String -LiteralPath $commandFile -Pattern 'transform\.(position|rotation)\s*=' -Quiet) {
        $failures.Add("Command path writes a presentation Transform: $commandFile")
    }
}

$forbiddenContractText = @(
    '/ws/v2/world',
    'baselineSequence',
    'worldVersion',
    'replication.frame',
    'replication.full_snapshot',
    'command.resync'
)
$contractFiles = @()
foreach ($relativeRoot in @('Assets', 'Packages', 'ProjectSettings', 'Tools')) {
    $contractRoot = Join-Path $projectRoot $relativeRoot
    if (Test-Path -LiteralPath $contractRoot) {
        $contractFiles += Get-ChildItem -LiteralPath $contractRoot -Recurse -File | Where-Object {
            $_.Extension -in @('.cs', '.md', '.ps1', '.json') -and $_.FullName -ne $PSCommandPath
        }
    }
}
$readme = Join-Path $projectRoot 'README.md'
if (Test-Path -LiteralPath $readme) {
    $contractFiles += Get-Item -LiteralPath $readme
}
foreach ($forbidden in $forbiddenContractText) {
    foreach ($contractFile in $contractFiles) {
        if (Select-String -LiteralPath $contractFile.FullName -SimpleMatch $forbidden -Quiet) {
            $failures.Add("Stale contract text '$forbidden' found in $($contractFile.FullName)")
        }
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output 'Unity static contract verification passed.'
Write-Output "Required files: $($required.Count)"
Write-Output "C# source files: $((Get-ChildItem -LiteralPath (Join-Path $projectRoot 'Assets') -Recurse -Filter '*.cs').Count)"
Write-Output 'Tracked generated directories: none'
Write-Output 'Tracked binary/private assets: none'
