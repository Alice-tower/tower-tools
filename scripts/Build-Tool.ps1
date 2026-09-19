[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Id,
    [switch]$SkipCatalog,
    [switch]$Clean
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$repoRoot = Get-RepoRoot
$projectPath = Find-ToolProject -Id $Id
$metadata = Read-ToolMetadata -ProjectPath $projectPath
$outputRoot = Join-Path $repoRoot 'outputs'
$stagingRoot = Join-Path $outputRoot '.staging'
$stage = Join-Path $stagingRoot ("tool-" + [Guid]::NewGuid().ToString('N'))
$prepared = Join-Path $stage $metadata.id

try {
    $tasks = @('test', 'createDistributable')
    if ($Clean) {
        $tasks = @('clean') + $tasks
    }
    Invoke-Gradle -ProjectPath $projectPath -Tasks $tasks
    $appRoot = Join-Path $projectPath 'build\compose\binaries\main\app'
    $executable = Get-ChildItem -LiteralPath $appRoot -Filter $metadata.executableName -File -Recurse -ErrorAction Stop | Select-Object -First 1
    if (-not $executable) {
        throw "Portable executable was not found below $appRoot"
    }

    [IO.Directory]::CreateDirectory($stage) | Out-Null
    Copy-Item -LiteralPath $executable.Directory.FullName -Destination $prepared -Recurse
    Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination (Join-Path $prepared 'LICENSE')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $prepared 'THIRD_PARTY_NOTICES.md')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'third-party') -Destination (Join-Path $prepared 'third-party') -Recurse
    Publish-Directory -PreparedDirectory $prepared -Destination (Join-Path $outputRoot "tools\$($metadata.id)") -AllowedOutputRoot $outputRoot
    Write-Host "Built $($metadata.displayName): outputs\tools\$($metadata.id)"
}
finally {
    if (Test-Path -LiteralPath $stage) {
        Remove-SafeDirectory -AllowedParent $stagingRoot -Target $stage
    }
}

if (-not $SkipCatalog) {
    Sync-TowerCatalog
}
