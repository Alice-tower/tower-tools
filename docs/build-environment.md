# Build environment

## Required software

- Windows 10 or newer, x64
- Azul Zulu JDK 21 x64
- PowerShell 5.1 or newer
- Internet access for the first Gradle dependency resolution
- IntelliJ IDEA is recommended but not required by the scripts

The scripts read `JAVA_HOME` from the current process, user environment, or machine environment. They fail with setup guidance if a Java 21 installation cannot be found.

## Portable packaging

Compose Desktop's `createDistributable` task creates a Windows application image containing an `.exe`, application libraries, and a trimmed Java runtime. It is a directory, not a single-file executable, and must be copied as a unit.

No MSI, shortcuts, signing, or automatic update mechanism is configured.
