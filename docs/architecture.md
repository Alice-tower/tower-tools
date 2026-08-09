# Architecture

## Goals

Tower Tools is a monorepo of independent Windows x64 desktop applications. Each tool can be opened on its own in IntelliJ IDEA, built independently, and copied as a portable directory without installing Java on the target machine.

## Projects

Each project is a single-module Kotlin/JVM application using Compose Desktop. Multiplatform source sets and non-Windows packaging are intentionally excluded. Google Maven is retained only because current Compose Desktop artifacts depend on AndroidX foundation components; no Android target is configured.

The source projects are independent Gradle builds so the repository remains responsive as the number of tools grows. Gradle Wrapper files are copied into every generated project to preserve reproducible standalone builds.

## Runtime data

Program binaries are replaceable. Mutable data is not stored beside an executable.

- Tool data: `%LOCALAPPDATA%\Alice-tower\TowerTools\<application-id>\`
- Launcher data: `%LOCALAPPDATA%\Alice-tower\TowerLauncher\`
- Logs are stored below each application's data directory in `logs/`.

## Portable output

`outputs/` is the only release location and is ignored by Git:

```text
outputs/
  launcher/               TowerLauncher.exe plus runtime files
  tools/<application-id>/ Tool.exe plus runtime files
  catalog/tools.json      generated runtime catalog
```

Catalog executable paths are relative to `outputs/`, so the complete directory can be moved without editing paths.

## Single-instance behavior

Every application uses a per-user lock file and a loopback activation channel derived from its stable application ID. A second process asks the first process to restore and focus its window, then exits.
