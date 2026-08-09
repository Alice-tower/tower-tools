# Tool lifecycle

## Create

Use `scripts/New-Tool.ps1`. The script validates identity fields, copies the template, replaces explicit tokens, moves Kotlin sources into the package path, creates metadata, builds a portable directory, synchronizes the catalog, and rebuilds the launcher.

The command accepts interactive input when a required value is omitted. Codex normally proposes the display name, project name, description, and ID before invoking it.

## Update

Edit the independent project below `apps/`, increment its version in `tool.json` and `build.gradle.kts`, then run `scripts/Build-Tool.ps1`. A successful build replaces only the program directory. User configuration and logs remain in `%LOCALAPPDATA%`.

## Remove

Use `scripts/Remove-Tool.ps1`. It prints exact source and output paths, requires confirmation unless `-Force` is explicitly supplied, validates that deletion targets remain inside the repository, removes the catalog entry and portable output, and rebuilds the launcher.

Launcher user overrides are deliberately retained when a tool is removed, allowing its category and order to return if the same stable ID is restored later.

## Failure behavior

Builds are assembled under `outputs/.staging/`. Existing portable output is replaced only after a new application image is complete. If a running executable locks the old directory, the operation stops with a clear error instead of terminating the application.
