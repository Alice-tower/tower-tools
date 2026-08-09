Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Get-RepoRoot {
    return $script:RepoRoot
}

function Write-Utf8File {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content
    )

    $parent = Split-Path -Parent $Path
    if ($parent) {
        [IO.Directory]::CreateDirectory($parent) | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Content, $script:Utf8NoBom)
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value,
        [int]$Depth = 8
    )

    $json = $Value | ConvertTo-Json -Depth $Depth
    Write-Utf8File -Path $Path -Content ($json + [Environment]::NewLine)
}

function Get-JavaHome {
    $candidates = @(
        $env:JAVA_HOME,
        [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User'),
        [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine'),
        'C:\Program Files\Zulu\zulu-21'
    ) | Where-Object { $_ } | Select-Object -Unique

    foreach ($candidate in $candidates) {
        $fullPath = [IO.Path]::GetFullPath($candidate.TrimEnd('\'))
        if (Test-Path -LiteralPath (Join-Path $fullPath 'bin\java.exe') -PathType Leaf) {
            return $fullPath
        }
    }

    throw 'JDK 21 was not found. Install Azul Zulu JDK 21 and configure JAVA_HOME.'
}

function Set-JavaEnvironment {
    $javaHome = Get-JavaHome
    $env:JAVA_HOME = $javaHome
    $javaBin = Join-Path $javaHome 'bin'
    if (-not (($env:Path -split ';') -contains $javaBin)) {
        $env:Path = "$javaBin;$env:Path"
    }
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectPath,
        [Parameter(Mandatory = $true)][string[]]$Tasks
    )

    Set-JavaEnvironment
    $wrapper = Join-Path $ProjectPath 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        throw "Gradle Wrapper was not found: $wrapper"
    }

    Push-Location $ProjectPath
    try {
        & $wrapper @Tasks '--no-daemon'
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed with exit code $LASTEXITCODE in $ProjectPath"
        }
    }
    finally {
        Pop-Location
    }
}

function Assert-ChildPath {
    param(
        [Parameter(Mandatory = $true)][string]$Parent,
        [Parameter(Mandatory = $true)][string]$Child
    )

    $parentFull = [IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    $childFull = [IO.Path]::GetFullPath($Child)
    if (-not $childFull.StartsWith($parentFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe path outside expected parent: $childFull"
    }
    return $childFull
}

function Remove-SafeDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$AllowedParent,
        [Parameter(Mandatory = $true)][string]$Target
    )

    $safeTarget = Assert-ChildPath -Parent $AllowedParent -Child $Target
    if (Test-Path -LiteralPath $safeTarget) {
        Remove-Item -LiteralPath $safeTarget -Recurse -Force
    }
}

function Publish-Directory {
    param(
        [Parameter(Mandatory = $true)][string]$PreparedDirectory,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$AllowedOutputRoot
    )

    $destinationFull = Assert-ChildPath -Parent $AllowedOutputRoot -Child $Destination
    $stagingRoot = Join-Path $AllowedOutputRoot '.staging'
    [IO.Directory]::CreateDirectory($stagingRoot) | Out-Null
    [IO.Directory]::CreateDirectory((Split-Path -Parent $destinationFull)) | Out-Null
    $backup = Join-Path $stagingRoot ("backup-" + [Guid]::NewGuid().ToString('N'))
    $hadPrevious = Test-Path -LiteralPath $destinationFull

    try {
        if ($hadPrevious) {
            Move-Item -LiteralPath $destinationFull -Destination $backup
        }
        Move-Item -LiteralPath $PreparedDirectory -Destination $destinationFull
        if ($hadPrevious) {
            Remove-SafeDirectory -AllowedParent $stagingRoot -Target $backup
        }
    }
    catch {
        if ((-not (Test-Path -LiteralPath $destinationFull)) -and (Test-Path -LiteralPath $backup)) {
            Move-Item -LiteralPath $backup -Destination $destinationFull
        }
        throw
    }
}

function Read-ToolMetadata {
    param([Parameter(Mandatory = $true)][string]$ProjectPath)

    $metadataPath = Join-Path $ProjectPath 'tool.json'
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw "Tool metadata was not found: $metadataPath"
    }
    return Get-Content -Raw -Encoding UTF8 -LiteralPath $metadataPath | ConvertFrom-Json
}

function Get-ToolProjects {
    $appsRoot = Join-Path $script:RepoRoot 'apps'
    if (-not (Test-Path -LiteralPath $appsRoot)) {
        return @()
    }

    return @(
        Get-ChildItem -LiteralPath $appsRoot -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'tool.json') -PathType Leaf } |
            Sort-Object Name
    )
}

function Find-ToolProject {
    param([Parameter(Mandatory = $true)][string]$Id)

    foreach ($project in Get-ToolProjects) {
        $metadata = Read-ToolMetadata -ProjectPath $project.FullName
        if ($metadata.id -eq $Id) {
            return $project.FullName
        }
    }
    throw "Tool was not found: $Id"
}

function New-CatalogEntry {
    param([Parameter(Mandatory = $true)]$Metadata)

    return [ordered]@{
        id = [string]$Metadata.id
        projectName = [string]$Metadata.projectName
        displayName = [string]$Metadata.displayName
        description = [string]$Metadata.description
        version = [string]$Metadata.version
        executablePath = "tools/$($Metadata.id)/$($Metadata.executableName)"
        defaultCategory = '未分类'
        defaultOrder = 0
    }
}

function Sync-TowerCatalog {
    $entries = @()
    foreach ($project in Get-ToolProjects) {
        $entries += New-CatalogEntry -Metadata (Read-ToolMetadata -ProjectPath $project.FullName)
    }
    $entries = @($entries | Sort-Object @{ Expression = { $_.displayName } }, @{ Expression = { $_.id } })

    $sourceCatalog = [ordered]@{
        schemaVersion = 1
        tools = $entries
    }
    Write-JsonFile -Path (Join-Path $script:RepoRoot 'catalog\tools.json') -Value $sourceCatalog

    $runtimeCatalog = [ordered]@{
        schemaVersion = 1
        generatedAt = [DateTimeOffset]::Now.ToString('o')
        tools = $entries
    }
    Write-JsonFile -Path (Join-Path $script:RepoRoot 'outputs\catalog\tools.json') -Value $runtimeCatalog
}

function ConvertTo-KebabCase {
    param([Parameter(Mandatory = $true)][string]$Value)

    return (($Value -creplace '([a-z0-9])([A-Z])', '$1-$2') -replace '[^A-Za-z0-9]+', '-').Trim('-').ToLowerInvariant()
}

function Escape-KotlinString {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)

    return $Value.Replace('\', '\\').Replace('"', '\"').Replace("`r", '').Replace("`n", '\n')
}

function Replace-TemplateTokens {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectPath,
        [Parameter(Mandatory = $true)][hashtable]$Replacements
    )

    $extensions = @('.kt', '.kts', '.properties', '.md', '.json', '.gitignore')
    $files = Get-ChildItem -LiteralPath $ProjectPath -Recurse -File | Where-Object {
        $extensions -contains $_.Extension -or $_.Name -eq '.gitignore'
    }

    foreach ($file in $files) {
        $content = [IO.File]::ReadAllText($file.FullName)
        foreach ($key in $Replacements.Keys) {
            $content = $content.Replace($key, [string]$Replacements[$key])
        }
        [IO.File]::WriteAllText($file.FullName, $content, $script:Utf8NoBom)
    }
}

function Move-TemplatePackage {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectPath,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    foreach ($sourceRootName in @('main', 'test')) {
        $kotlinRoot = Join-Path $ProjectPath "src\$sourceRootName\kotlin"
        $placeholder = Join-Path $kotlinRoot '__APP_PACKAGE_PATH__'
        if (-not (Test-Path -LiteralPath $placeholder)) {
            continue
        }
        $packagePath = $PackageName.Replace('.', '\')
        $destination = Join-Path $kotlinRoot $packagePath
        [IO.Directory]::CreateDirectory($destination) | Out-Null
        Get-ChildItem -LiteralPath $placeholder -File | ForEach-Object {
            Move-Item -LiteralPath $_.FullName -Destination $destination
        }
        Remove-SafeDirectory -AllowedParent $kotlinRoot -Target $placeholder
    }
}
