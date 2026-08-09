[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'Common.ps1')

Sync-TowerCatalog
Write-Host 'Tool catalogs synchronized.'
