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
| Java Native Access (`jna`, `jna-platform`) | 5.19.1 | Apache-2.0 OR LGPL-2.1-or-later; this distribution uses the Apache-2.0 option | `third-party/licenses/JNA-LICENSE.txt` and `third-party/licenses/Apache-2.0.txt`; [source](https://github.com/java-native-access/jna) |

The bundled Azul Zulu Java 21 runtime contains its own license and
third-party notices in each application's `runtime/legal/` directory. Preserve
that directory when distributing the portable application.

FFmpeg, Whisper, CUDA and model files are selected by the user and are not
included in these portable outputs. Their licenses therefore are not covered
by this notice.
