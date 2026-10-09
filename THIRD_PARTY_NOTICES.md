# Third-party notices for Tower Tools portable builds

This file covers the third-party components found in the Windows x64 portable
output for the launcher and current tools. Each application contains a subset
of the JARs listed below. The project code is licensed separately under the
root `LICENSE` (MIT). The texts in `third-party/licenses/` are included with
the portable output.

| Component in portable output | Version(s) observed | License | License/source |
| --- | --- | --- | --- |
| Compose Desktop and JetBrains Compose/AndroidX Compose UI, foundation, material, runtime and related AndroidX libraries (`animation-*`, `annotation-*`, `collection-*`, `core-common`, `desktop-jvm`, `foundation-*`, `lifecycle-*`, `material-*`, `navigationevent-*`, `runtime-*`, `savedstate-*`, `ui-*`) | 1.11.1, 1.11.2 and the versions in the JAR filenames | Apache-2.0 | `third-party/licenses/Apache-2.0.txt`; component licenses are also embedded in many JARs. [Compose source](https://github.com/JetBrains/compose-multiplatform) |
| Kotlin standard library and JetBrains annotations (`kotlin-stdlib`, `annotations`, `jbr-api`) | 2.4.10, 23.0.0, 1.9.0 | Apache-2.0 | `third-party/licenses/Apache-2.0.txt`; [Kotlin](https://github.com/JetBrains/kotlin), [annotations](https://github.com/JetBrains/java-annotations), [JBR API](https://github.com/JetBrains/JetBrainsRuntimeApi) |
| KotlinX coroutines, serialization and AtomicFU (`kotlinx-*`, `atomicfu-jvm`) | 1.9.0, 1.7.3, 0.28.0 | Apache-2.0 | `third-party/licenses/Apache-2.0.txt`; [coroutines](https://github.com/Kotlin/kotlinx.coroutines), [serialization](https://github.com/Kotlin/kotlinx.serialization), [AtomicFU](https://github.com/Kotlin/kotlinx-atomicfu) |
| JSpecify (`jspecify`) | 1.0.0 | Apache-2.0 | `third-party/licenses/Apache-2.0.txt`; [source](https://github.com/jspecify/jspecify) |
| Skiko (`skiko-awt`, `skiko-awt-runtime-windows-x64`, `skiko-windows-x64.dll`) | 0.144.6 | Apache-2.0 | `third-party/licenses/Apache-2.0.txt`; [source](https://github.com/JetBrains/skiko) |
| Skia graphics code in the Skiko native library | bundled with Skiko 0.144.6 | BSD-3-Clause | `third-party/licenses/Skia-BSD-3-Clause.txt`; [source](https://github.com/google/skia/blob/main/LICENSE) |
| TwelveMonkeys ImageIO (`common-*`, `imageio-*`) | 3.14.0 | BSD-3-Clause | `third-party/licenses/TwelveMonkeys-BSD-3-Clause.txt`; [source](https://github.com/haraldk/TwelveMonkeys/blob/master/LICENSE.txt) |
| metadata-extractor | 2.21.0 | Apache-2.0 | `third-party/licenses/Apache-2.0.txt`; [source](https://github.com/drewnoakes/metadata-extractor) |
| Adobe XMPCore (`xmpcore`) | 6.1.11 | BSD-3-Clause | `third-party/licenses/Adobe-XMPCore-BSD-3-Clause.txt`; [published license metadata](https://central.sonatype.com/artifact/com.adobe.xmp/xmpcore), [Adobe license text](https://github.com/adobe/XMP-Toolkit-SDK/blob/main/LICENSE) |
| webp-imageio, including its native WebP reader/writer | 0.11.0 | Apache-2.0 | `third-party/licenses/Apache-2.0.txt` and `third-party/licenses/webp-imageio-NOTICE.txt`, extracted from the distributed JAR; [source](https://github.com/usefulness/webp-imageio) |
| libwebp used by the WebP native reader/writer | bundled with webp-imageio 0.11.0 | BSD-3-Clause | `third-party/licenses/libwebp-BSD-3-Clause.txt`; [source](https://github.com/webmproject/libwebp/blob/main/COPYING) |
| Java Native Access (`jna`, `jna-platform`), used by the Windows taskbar identity setup and proxy manager | 5.19.1 | Apache-2.0 OR LGPL-2.1-or-later; this distribution uses the Apache-2.0 option | `third-party/licenses/JNA-LICENSE.txt` and `third-party/licenses/Apache-2.0.txt`; [source](https://github.com/java-native-access/jna) |
| GitHub Invertocat mark used in the launcher's GITHUB button | Primer Octicons `mark-github-16` (2026) | GitHub trademark; used as a link to GitHub under the brand usage guidelines | [Octicons source](https://github.com/primer/octicons/blob/main/icons/mark-github-16.svg), [GitHub logo guidelines](https://brand.github.com/foundations/logo) |
| Visual Studio Code stable icon used in the launcher's VS Code button | from Visual Studio Code's `code-icon.svg` | Microsoft trademark; used to identify the action that opens a folder in VS Code | [VS Code icon and action button guidelines](https://code.visualstudio.com/brand) |
| Xerial SQLite JDBC (`sqlite-jdbc`, including bundled `sqlitejdbc.dll`) | 3.50.3.0 | Apache-2.0; inherited Zentus code is BSD-2-Clause | `third-party/licenses/Apache-2.0.txt`, `third-party/licenses/SQLite-JDBC-Zentus.txt`; the original licenses are also embedded in the JAR. [Versioned source](https://github.com/xerial/sqlite-jdbc/tree/3.50.3.0) |
| SQLite engine in SQLite JDBC native libraries | 3.50.3 | Public domain | [SQLite copyright statement](https://www.sqlite.org/copyright.html); [JDBC release](https://github.com/xerial/sqlite-jdbc/releases/tag/3.50.3.0) |
| Apache PDFBox, PDFBox IO and FontBox (`pdfbox`, `pdfbox-io`, `fontbox`) | 3.0.8 | Apache-2.0; bundled resources retain their additional licenses (including Adobe, Unicode, BSD, CC0 and SIL OFL) | Full `pdfbox-LICENSE.txt`, `pdfbox-NOTICE.txt`, `pdfbox-io-LICENSE.txt`, `pdfbox-io-NOTICE.txt`, `fontbox-LICENSE.txt`, `fontbox-NOTICE.txt` in `third-party/licenses/`, extracted from the actual packaged JARs; [versioned source](https://github.com/apache/pdfbox/tree/3.0.8) |
| Apache Commons Logging (`commons-logging`) | 1.4.0 | Apache-2.0 | `third-party/licenses/commons-logging-LICENSE.txt` and `commons-logging-NOTICE.txt`, extracted from the actual JAR; [source](https://commons.apache.org/proper/commons-logging/) |
| jsoup (`jsoup`) | 1.23.2 | MIT | `third-party/licenses/jsoup-LICENSE.txt`, extracted from the actual JAR; [source](https://github.com/jhy/jsoup/tree/jsoup-1.23.2) |
| OpenJFX Windows modules (`javafx-base`, `javafx-graphics`, `javafx-controls`, `javafx-media`, `javafx-web`, `javafx-swing`) | 21.0.12 | GPL-2.0-only WITH Classpath-exception-2.0, with separate third-party licenses | Full module legal directories under `third-party/licenses/OpenJFX-21.0.12/`, extracted from the same-version official Windows SDK; [official download index](https://gluonhq.com/products/javafx/), [source](https://github.com/openjdk/jfx21u) |
| Native JPEG, Mesa, DirectShow samples, GLib, GStreamer, libffi, ICU, libxml2, libxslt and WebKit code inside OpenJFX DLLs; ICU data (`icudtl.dat`) | versions documented in the OpenJFX legal texts | IJG; MIT/BSD; LGPL-2.1 and additional MPL-2.0, Apache-2.0, Unicode and BSD licenses in WebKit | Preserve all `javafx.graphics/*.md`, `javafx.media/*.md`, `javafx.web/*.md` under `third-party/licenses/OpenJFX-21.0.12/`; the full WebKit text includes its embedded projects' notices |
| Microsoft MSVC and Universal C Runtime (`msvcp140*.dll`, `vcruntime140*.dll`, `ucrtbase.dll`, `api-ms-win-*.dll`) inside the OpenJFX graphics Windows JAR | versions carried in the 21.0.12 Windows SDK/JAR | Microsoft redistributable runtime terms | `third-party/licenses/Microsoft-Visual-Studio-2022-Community-License.txt` and `Microsoft-Runtime-PROVENANCE.md` preserve the official license reference and audit origin; [Visual Studio 2022 redistribution list](https://learn.microsoft.com/en-us/visualstudio/releases/2022/redistribution), [licensing guidance](https://www.microsoft.com/licensing/guidance/Visual-Studio). See `apps/tower-reader/DEPENDENCIES.md` for the audit and source-distribution requirements |
| Microsoft WebView2 SDK Windows x64 loader (`WebView2Loader.dll`) | 1.0.4258.31 | BSD-3-Clause SDK license; preserve accompanying notices | `third-party/licenses/WebView2-SDK-LICENSE.txt` and `WebView2-SDK-NOTICE.txt`, extracted from the official SDK package; [package](https://www.nuget.org/packages/Microsoft.Web.WebView2/1.0.4258.31). The installed Evergreen WebView2 Runtime is reused and is not bundled. |

The bundled Azul Zulu Java 21 runtime contains its own license and
third-party notices in each application's `runtime/legal/` directory. Preserve
that directory when distributing the portable application.

FFmpeg, Whisper, CUDA and model files are selected by the user and are not
included in these portable outputs. Their licenses therefore are not covered
by this notice.

ProxyEnvManager 1.1.0 adds test-only Compose UI testing 1.11.1 (Apache-2.0)
and JUnit Vintage 5.10.1 (EPL-2.0), with JUnit 4.13.2 (EPL-1.0) and Hamcrest
1.3 (BSD-3-Clause) on the test classpath. These reuse the repository's existing
test components and are excluded from portable outputs. The packaged runtime
components and versions remain covered by the table above.
