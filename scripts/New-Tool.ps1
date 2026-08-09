[CmdletBinding()]
param(
    [string]$DisplayName,
    [string]$ProjectName,
    [string]$Description,
    [string]$Id,
    [string]$Version = '1.0.0'
)

. (Join-Path $PSScriptRoot 'Common.ps1')

if (-not $DisplayName) { $DisplayName = Read-Host '中文显示名称' }
if (-not $ProjectName) { $ProjectName = Read-Host '英文工程名称（PascalCase）' }
if (-not $Description) { $Description = Read-Host '工具用途' }
if (-not $Id) { $Id = 'dev.towertools.' + (($ProjectName -replace '[^A-Za-z0-9]', '').ToLowerInvariant()) }

if ($ProjectName -notmatch '^[A-Z][A-Za-z0-9]*$') {
    throw 'ProjectName must use PascalCase ASCII letters and digits.'
}
if ($Id -notmatch '^dev\.towertools\.[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*$') {
    throw 'Id must be a lowercase identifier below dev.towertools.'
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw 'Version must use major.minor.patch format.'
}
if ([string]::IsNullOrWhiteSpace($DisplayName) -or [string]::IsNullOrWhiteSpace($Description)) {
    throw 'DisplayName and Description cannot be empty.'
}

$repoRoot = Get-RepoRoot
$appsRoot = Join-Path $repoRoot 'apps'
$template = Join-Path $repoRoot 'template\windows-compose-app'
$directoryName = ConvertTo-KebabCase -Value $ProjectName
$projectPath = Join-Path $appsRoot $directoryName
$outputPath = Join-Path $repoRoot "outputs\tools\$Id"

if (Test-Path -LiteralPath $projectPath) {
    throw "Project directory already exists: $projectPath"
}
foreach ($project in Get-ToolProjects) {
    $existing = Read-ToolMetadata -ProjectPath $project.FullName
    if ($existing.id -eq $Id -or $existing.projectName -eq $ProjectName) {
        throw "Tool identity already exists: $($existing.id)"
    }
}

$created = $false
try {
    Copy-Item -LiteralPath $template -Destination $projectPath -Recurse
    $created = $true
    $uuid = [Guid]::NewGuid().ToString()
    $packageName = $Id
    $replacements = @{
        '__APP_DISPLAY_NAME__' = $DisplayName
        '__APP_DISPLAY_NAME_KOTLIN__' = (Escape-KotlinString -Value $DisplayName)
        '__APP_PROJECT_NAME__' = $ProjectName
        '__APP_ID__' = $Id
        '__APP_PACKAGE__' = $packageName
        '__APP_DESCRIPTION__' = $Description
        '__APP_DESCRIPTION_KOTLIN__' = (Escape-KotlinString -Value $Description)
        '__APP_VERSION__' = $Version
        '__APP_UUID__' = $uuid
    }
    Replace-TemplateTokens -ProjectPath $projectPath -Replacements $replacements
    Move-TemplatePackage -ProjectPath $projectPath -PackageName $packageName

    $metadata = [ordered]@{
        id = $Id
        projectName = $ProjectName
        displayName = $DisplayName
        description = $Description
        version = $Version
        author = 'Alice-tower'
        uuid = $uuid
        executableName = "$ProjectName.exe"
    }
    Write-JsonFile -Path (Join-Path $projectPath 'tool.json') -Value $metadata

    & (Join-Path $PSScriptRoot 'Build-Tool.ps1') -Id $Id
    & (Join-Path $PSScriptRoot 'Build-Launcher.ps1')
    Write-Host "Created $DisplayName at $projectPath"
}
catch {
    if ($created -and (Test-Path -LiteralPath $projectPath)) {
        Remove-SafeDirectory -AllowedParent $appsRoot -Target $projectPath
    }
    if (Test-Path -LiteralPath $outputPath) {
        Remove-SafeDirectory -AllowedParent (Join-Path $repoRoot 'outputs\tools') -Target $outputPath
    }
    Sync-TowerCatalog
    throw
}
