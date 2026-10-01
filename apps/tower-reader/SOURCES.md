# OpenJFX source distribution manifest

Downloaded 2026-10-01. Preserve both archives with TowerReader's portable output.

| File | Origin | SHA-256 |
| --- | --- | --- |
| openjfx-21.0.12-source.zip | https://codeload.github.com/openjdk/jfx21u/zip/refs/tags/21.0.12%2B3 | 5c5b9826c8bfbfd571ba97e15128055f69226d968a10e913f2194ee5ec29b09a |
| openjfx-21.0.12-sdk-generated-java-source.zip | Original src.zip from https://download2.gluonhq.com/openjfx/21.0.12/openjfx-21.0.12_windows-x64_bin-sdk.zip | 417e52996929692bd64c3aae4a9f7a12c512816637f7a61588452bdefc8f6389 |

Official source tag: 21.0.12+3, commit 6ae7c978ef7cc7900281d49712cea5bf529858d0.
Gluon SDK runtime property: 21.0.12+2. No +2 tag exists in the official
OpenJDK jfx21u repository at the audit date. 2352 non-generated Java files and
all 10 native license documents match exactly; 110 generated Java files are
retained in the SDK source archive. All 58 native DLL/ICU data entries in the
actual Maven Windows JARs match the official SDK by SHA-256.

The full source archive includes native WebKit, GLib, GStreamer and build
scripts. The SDK Java src.zip alone is insufficient for native source access.
The available publisher metadata does not identify the precise native source
commit for the +2 binary; do not describe the tag number as the binary build
number or claim native source reproducibility from Java/legal matching alone.

Full audit and Microsoft runtime references: apps/tower-reader/DEPENDENCIES.md
in the Tower Tools source repository. Full preserved licenses are supplied in
third-party/licenses/OpenJFX-21.0.12/ with the portable application.
