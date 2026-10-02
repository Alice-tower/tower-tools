import java.security.MessageDigest
import java.nio.file.Path
import java.nio.file.Files
import java.util.HexFormat
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.zip.ZipFile
plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "dev.towertools.towerreader"
version = "1.2.2"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    implementation("org.jetbrains.compose.material:material:1.11.1")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.jsoup:jsoup:1.23.2")
    listOf("base", "graphics", "controls", "media", "web", "swing").forEach {
        implementation("org.openjfx:javafx-$it:21.0.12:win") { isTransitive = false }
    }
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.11.1")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.1")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("compose.interop.blending", "true")
    environment("TOWER_READER_VERIFY_WEREAD", providers.environmentVariable("TOWER_READER_VERIFY_WEREAD").getOrElse("0"))
    environment("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", providers.environmentVariable("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS").getOrElse(""))
    environment("TOWER_READER_REGRESSION_EPUB", providers.environmentVariable("TOWER_READER_REGRESSION_EPUB").getOrElse(""))
    environment("LOCALAPPDATA", layout.buildDirectory.dir("test-local-data").get().asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "dev.towertools.towerreader.MainKt"

        nativeDistributions {
            modules("java.sql", "jdk.unsupported", "jdk.unsupported.desktop", "jdk.jsobject", "jdk.xml.dom", "java.net.http", "java.scripting", "java.management", "jdk.charsets")
            packageName = "TowerReader"
            packageVersion = "1.2.2"
            vendor = "Alice-tower"
            windows {
                iconFile.set(project.file("icons/app-icon.ico"))
            }
        }
    }
}

// Preserve full upstream sources alongside the portable image. The cache lives
// under the repository's ignored outputs/ and is reusable across clean builds.
abstract class PrepareReaderSources : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.OutputFile
    abstract val fullSource: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.OutputFile
    abstract val generatedJavaSource: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.Input
    abstract val sourceUrl: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val sourceSha256: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val sdkUrl: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val sdkSha256: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val generatedSha256: org.gradle.api.provider.Property<String>

    private fun digest(path: Path): String {
        val sha = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                sha.update(bytes, 0, count)
            }
        }
        return HexFormat.of().formatHex(sha.digest())
    }

    private fun valid(path: Path, expected: String): Boolean =
        Files.isRegularFile(path) && digest(path) == expected

    private fun download(url: String, target: Path, expected: String) {
        val connection = URI.create(url).toURL().openConnection().apply {
            connectTimeout = 120000
            readTimeout = 120000
            setRequestProperty("User-Agent", "TowerReader-source-distribution")
        }
        connection.getInputStream().use { input ->
            Files.newOutputStream(target).use { output -> input.copyTo(output) }
        }
        check(valid(target, expected)) { "Downloaded upstream source checksum mismatch: $url" }
    }

    private fun publish(temp: Path, target: Path) {
        try {
            Files.move(temp, target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @org.gradle.api.tasks.TaskAction
    fun prepare() {
        val full = fullSource.get().asFile.toPath()
        val generated = generatedJavaSource.get().asFile.toPath()
        Files.createDirectories(full.parent)
        FileChannel.open(full.parent.resolve(".source-download.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (!valid(full, sourceSha256.get())) {
                    val temp = Files.createTempFile(full.parent, "openjfx-source-", ".part")
                    try {
                        download(sourceUrl.get(), temp, sourceSha256.get())
                        publish(temp, full)
                    } finally { Files.deleteIfExists(temp) }
                }
                if (!valid(generated, generatedSha256.get())) {
                    val sdk = Files.createTempFile(full.parent, "openjfx-sdk-", ".part")
                    val temp = Files.createTempFile(full.parent, "openjfx-java-", ".part")
                    try {
                        download(sdkUrl.get(), sdk, sdkSha256.get())
                        ZipFile(sdk.toFile()).use { archive ->
                            val entry = checkNotNull(archive.getEntry("javafx-sdk-21.0.12/src.zip")) {
                                "Official OpenJFX SDK does not contain src.zip"
                            }
                            archive.getInputStream(entry).use { input ->
                                Files.newOutputStream(temp).use { output -> input.copyTo(output) }
                            }
                        }
                        check(valid(temp, generatedSha256.get())) { "SDK Java source checksum mismatch" }
                        publish(temp, generated)
                    } finally {
                        Files.deleteIfExists(temp)
                        Files.deleteIfExists(sdk)
                    }
                }
            }
        }
    }
}

val prepareReaderSources = tasks.register<PrepareReaderSources>("prepareReaderSources") {
    fullSource.set(layout.projectDirectory.file("../../outputs/third-party-sources/tower-reader/openjfx-21.0.12-source.zip"))
    generatedJavaSource.set(layout.projectDirectory.file("../../outputs/third-party-sources/tower-reader/openjfx-21.0.12-sdk-generated-java-source.zip"))
    sourceUrl.set("https://codeload.github.com/openjdk/jfx21u/zip/refs/tags/21.0.12%2B3")
    sourceSha256.set("5c5b9826c8bfbfd571ba97e15128055f69226d968a10e913f2194ee5ec29b09a")
    sdkUrl.set("https://download2.gluonhq.com/openjfx/21.0.12/openjfx-21.0.12_windows-x64_bin-sdk.zip")
    sdkSha256.set("78ab10816e71ed51808bc5853bb26f38cf35b550a4cec233dbc4fbf5a3e8574c")
    generatedSha256.set("417e52996929692bd64c3aae4a9f7a12c512816637f7a61588452bdefc8f6389")
    // Recheck cached digests every packaging run, including a reused configuration cache.
    outputs.upToDateWhen { false }
}

val distributeReaderSources = tasks.register<Copy>("distributeReaderSources") {
    dependsOn(prepareReaderSources)
    from(prepareReaderSources.flatMap { it.fullSource })
    from(prepareReaderSources.flatMap { it.generatedJavaSource })
    from(layout.projectDirectory.file("SOURCES.md"))
    from(layout.projectDirectory.file("DEPENDENCIES.md"))
    into(layout.buildDirectory.dir("compose/binaries/main/app/TowerReader/third-party-sources"))
    mustRunAfter("createDistributable")
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(prepareReaderSources)
    finalizedBy(distributeReaderSources)
}

val distributeReaderGuide = tasks.register<Copy>("distributeReaderGuide") {
    from(layout.projectDirectory.file("README.md"))
    from(layout.projectDirectory.file("VERIFICATION.md"))
    into(layout.buildDirectory.dir("compose/binaries/main/app/TowerReader"))
    mustRunAfter("createDistributable")
}
tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy(distributeReaderGuide)
}

// Only the signed Windows x64 loader is bundled; the installed Evergreen runtime is reused.
abstract class PrepareWebViewLoader : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.OutputFile
    abstract val loader: org.gradle.api.file.RegularFileProperty
    @get:org.gradle.api.tasks.Internal
    abstract val archive: org.gradle.api.file.RegularFileProperty
    @org.gradle.api.tasks.TaskAction
    fun prepare() {
        val cache = archive.get().asFile.toPath()
        val expected = "56f7f4b8bf9aee4b8efefbbdd4f67d5f74ebd1b100ed0806da71bf76af481aa9"
        fun valid() = Files.isRegularFile(cache) && HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(cache))) == expected
        Files.createDirectories(cache.parent)
        if (!valid()) {
            val temp = Files.createTempFile(cache.parent, "webview2-", ".tmp")
            try {
                val connection = URI.create("https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/1.0.4258.31/microsoft.web.webview2.1.0.4258.31.nupkg").toURL().openConnection().apply {
                    connectTimeout = 30000; readTimeout = 120000
                }
                connection.getInputStream().use { input -> Files.newOutputStream(temp).use(input::copyTo) }
                check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(temp))) == expected)
                Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        }
        val target = loader.get().asFile.toPath(); Files.createDirectories(target.parent)
        ZipFile(cache.toFile()).use { zip ->
            zip.getInputStream(checkNotNull(zip.getEntry("build/native/x64/WebView2Loader.dll"))).use { input ->
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
val prepareWebViewLoader = tasks.register<PrepareWebViewLoader>("prepareWebViewLoader") {
    archive.set(layout.projectDirectory.file("../../outputs/build-dependencies/tower-reader/microsoft.web.webview2.1.0.4258.31.nupkg"))
    loader.set(layout.buildDirectory.file("generated/webview2/native/windows-x64/WebView2Loader.dll"))
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/webview2")) }
tasks.named("processResources") { dependsOn(prepareWebViewLoader) }
