# Project conventions

## Technology

- Windows 10 or newer, x64
- JDK 21 toolchain
- Kotlin/JVM
- Compose Desktop
- Stable dependencies where practical
- UTF-8 source files

## Identity

Every tool has four distinct identity fields:

- `displayName`: Chinese user-facing name.
- `projectName`: PascalCase English name used for the Gradle project and executable.
- `id`: stable lowercase application ID below `dev.towertools`.
- `directoryName`: kebab-case source directory below `apps/`.

The Kotlin package and Gradle group equal the application ID. IDs must never be reused for a different tool or changed after release.

The author/vendor is `Alice-tower`. No open-source license is added by default.

## Versions

Applications begin at `1.0.0` and use semantic versions. Portable outputs replace the previous output for the same application ID after a successful staged build.

## User interface

All tools expose a GUI. The default template provides a Chinese interface, system light/dark theme support, standard window sizing, a visible error surface, logging, and single-instance activation.

Small applications should stay structurally simple. Add architectural layers only when the feature set requires them.
