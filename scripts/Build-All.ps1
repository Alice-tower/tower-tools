[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'Common.ps1')

foreach ($project in Get-ToolProjects) {
    $metadata = Read-ToolMetadata -ProjectPath $project.FullName
    & (Join-Path $PSScriptRoot 'Build-Tool.ps1') -Id $metadata.id -SkipCatalog
}

Sync-TowerCatalog
& (Join-Path $PSScriptRoot 'Build-Launcher.ps1')
