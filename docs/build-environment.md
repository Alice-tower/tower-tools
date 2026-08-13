# Build environment

## Required software

- Windows 10 or newer, x64
- Azul Zulu JDK 21 x64
- PowerShell 5.1 or newer
- Internet access for the first Gradle dependency resolution
- IntelliJ IDEA is recommended but not required by the scripts

The scripts read `JAVA_HOME` from the current process, user environment, or machine environment. They fail with setup guidance if a Java 21 installation cannot be found.

## Repository build cache

Lifecycle scripts set `GRADLE_USER_HOME` to the ignored `.gradle-user-home/` directory at the repository root. The launcher and every independent tool therefore reuse one Gradle distribution, dependency cache, Compose packaging tools, and local build cache without relying on Java's machine-dependent `user.home` value.

Gradle Wrapper downloads use a 120-second network timeout. Internet access is still required to populate a cold cache, but later builds reuse downloaded artifacts.

Build scripts are incremental by default. Pass `-Clean` to `Build-Tool.ps1`, `Build-Launcher.ps1`, or `Build-All.ps1` only when a clean rebuild is required for diagnosis or verification.

On machines that block unsigned PowerShell scripts, invoke lifecycle commands with `powershell.exe -NoProfile -ExecutionPolicy Bypass -File`. This changes policy only for that child process. Managed automation sandboxes may additionally require build permission because Gradle runs Java child processes and writes project build directories.

## Portable packaging

Compose Desktop's `createDistributable` task creates a Windows application image containing an `.exe`, application libraries, and a trimmed Java runtime. It is a directory, not a single-file executable, and must be copied as a unit.

No MSI, shortcuts, signing, or automatic update mechanism is configured.
