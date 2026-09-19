[CmdletBinding()]
param(
    [switch]$Clean
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$repoRoot = Get-RepoRoot
$projectPath = Join-Path $repoRoot 'launcher\TowerLauncher'
$outputRoot = Join-Path $repoRoot 'outputs'
$stagingRoot = Join-Path $outputRoot '.staging'
$stage = Join-Path $stagingRoot ("launcher-" + [Guid]::NewGuid().ToString('N'))
$prepared = Join-Path $stage 'launcher'

Sync-TowerCatalog

try {
    $tasks = @('test', 'createDistributable')
    if ($Clean) {
        $tasks = @('clean') + $tasks
    }
    Invoke-Gradle -ProjectPath $projectPath -Tasks $tasks
    $appRoot = Join-Path $projectPath 'build\compose\binaries\main\app'
    $executable = Get-ChildItem -LiteralPath $appRoot -Filter 'TowerLauncher.exe' -File -Recurse -ErrorAction Stop | Select-Object -First 1
    if (-not $executable) {
        throw "TowerLauncher.exe was not found below $appRoot"
    }

    [IO.Directory]::CreateDirectory($stage) | Out-Null
    Copy-Item -LiteralPath $executable.Directory.FullName -Destination $prepared -Recurse
    Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination (Join-Path $prepared 'LICENSE')
    Publish-Directory -PreparedDirectory $prepared -Destination (Join-Path $outputRoot 'launcher') -AllowedOutputRoot $outputRoot
    Write-Host 'Built 工具塔: outputs\launcher'
}
finally {
    if (Test-Path -LiteralPath $stage) {
        Remove-SafeDirectory -AllowedParent $stagingRoot -Target $stage
    }
}
