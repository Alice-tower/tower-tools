[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Id,
    [switch]$Force
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$repoRoot = Get-RepoRoot
$appsRoot = Join-Path $repoRoot 'apps'
$toolsOutputRoot = Join-Path $repoRoot 'outputs\tools'
$projectPath = Find-ToolProject -Id $Id
$metadata = Read-ToolMetadata -ProjectPath $projectPath
$outputPath = Join-Path $toolsOutputRoot $Id

Write-Host "Tool: $($metadata.displayName) ($Id)"
Write-Host "Source: $projectPath"
Write-Host "Output: $outputPath"

if (-not $Force) {
    $answer = Read-Host 'Type REMOVE to confirm'
    if ($answer -cne 'REMOVE') {
        Write-Host 'Removal cancelled.'
        return
    }
}

Remove-SafeDirectory -AllowedParent $appsRoot -Target $projectPath
if (Test-Path -LiteralPath $outputPath) {
    Remove-SafeDirectory -AllowedParent $toolsOutputRoot -Target $outputPath
}
Sync-TowerCatalog
& (Join-Path $PSScriptRoot 'Build-Launcher.ps1')
Write-Host "Removed $($metadata.displayName). User settings were preserved."
