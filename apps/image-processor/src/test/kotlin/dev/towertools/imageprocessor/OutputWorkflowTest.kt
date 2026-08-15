package dev.towertools.imageprocessor

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.stream.FileImageOutputStream
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OutputWorkflowTest {
    @Test
    fun `png split export keeps source unchanged and writes ordered pieces`() {
        val root = createTempDirectory("image-processor-test-")
        val sourcePath = root.resolve("示例.png")
        val outputDirectory = root.resolve("output").createDirectory()
        writeSamplePng(sourcePath)
        val originalBytes = Files.readAllBytes(sourcePath)
        val loaded = ImageIOService.load(sourcePath)

        val result = OutputExporter.export(
            loaded,
            Rotation.CLOCKWISE_90,
            cropPresets.first { it.label == "1:1" },
            SplitMode.GRID_2_2,
            outputDirectory,
        )

        assertEquals(4, result.files.size)
        assertEquals(
            listOf("示例_r90_c1x1_1.png", "示例_r90_c1x1_2.png", "示例_r90_c1x1_3.png", "示例_r90_c1x1_4.png"),
            result.files.map { it.fileName.toString() },
        )
        result.files.forEach { assertTrue(ImageIO.read(it.toFile()).width > 0) }
        assertContentEquals(originalBytes, Files.readAllBytes(sourcePath))
    }

    @Test
    fun `fine crop exports the selected pixel rectangle without scaling`() {
        val root = createTempDirectory("image-processor-fine-crop-")
        val sourcePath = root.resolve("精细.png")
        val outputDirectory = root.resolve("output").createDirectory()
        writeSamplePng(sourcePath)
        val loaded = ImageIOService.load(sourcePath)

        val result = OutputExporter.export(
            loaded,
            Rotation.CLOCKWISE_90,
            CropMode.Fine(ImageRect(1, 2, 7, 5)),
            SplitMode.ORIGINAL,
            outputDirectory,
        )

        val output = ImageIO.read(result.files.single().toFile())
        assertEquals(7, output.width)
        assertEquals(5, output.height)
        assertTrue(result.files.single().fileName.toString().contains("r90_cfine_1x2_7x5"))
    }

    @Test
    fun `any collision rejects the whole export`() {
        val root = createTempDirectory("image-processor-collision-")
        val sourcePath = root.resolve("photo.png")
        val outputDirectory = root.resolve("output").createDirectory()
        writeSamplePng(sourcePath)
        val loaded = ImageIOService.load(sourcePath)
        Files.writeString(outputDirectory.resolve("photo_r0_coriginal_2.png"), "existing")

        val error = assertFailsWith<OutputCollisionException> {
            OutputExporter.export(
                loaded,
                Rotation.ORIGINAL,
                CropMode.Original,
                SplitMode.LEFT_RIGHT,
                outputDirectory,
            )
        }
        assertEquals(1, error.collisions.size)
        assertTrue(!Files.exists(outputDirectory.resolve("photo_r0_coriginal_1.png")))
    }

    @Test
    fun `all promised formats have readers and writers`() {
        ImageIO.scanForPlugins()
        for (format in listOf("jpeg", "png", "bmp", "gif", "webp", "tiff")) {
            assertTrue(ImageIO.getImageReadersByFormatName(format).hasNext(), "Missing reader for $format")
            assertTrue(ImageIO.getImageWritersByFormatName(format).hasNext(), "Missing writer for $format")
        }
    }

    @Test
    fun `all promised static formats round trip through loader and exporter`() {
        val root = createTempDirectory("image-processor-formats-")
        val formats = listOf(
            Triple("jpg", "jpeg", "JPEG"),
            Triple("png", "png", "PNG"),
            Triple("bmp", "bmp", "BMP"),
            Triple("gif", "gif", "GIF"),
            Triple("webp", "webp", "WEBP"),
            Triple("tif", "tiff", "TIFF"),
        )
        formats.forEach { (extension, writerName, expectedFormat) ->
            val source = root.resolve("source.$extension")
            val output = root.resolve("out-$extension").createDirectory()
            val image = BufferedImage(13, 9, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color(25, 110, 210)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.ORANGE
            graphics.fillRect(0, 0, 5, 4)
            graphics.dispose()
            assertTrue(ImageIO.write(image, writerName, source.toFile()), "Unable to create $writerName fixture")

            val loaded = ImageIOService.load(source)
            assertEquals(expectedFormat, loaded.descriptor.format)
            val result = OutputExporter.export(
                loaded,
                Rotation.ORIGINAL,
                CropMode.Original,
                SplitMode.ORIGINAL,
                output,
            )
            assertEquals(1, result.files.size)
            assertTrue(Files.size(result.files.single()) > 0)
            assertTrue(ImageIO.read(result.files.single().toFile()) != null)
        }
    }

    @Test
    fun `path validator requires an existing writable absolute directory`() {
        val root = createTempDirectory("image-processor-path-")
        assertEquals(root.toAbsolutePath().normalize(), PathValidator.requireWritable(root.toString()))
        assertFailsWith<UserFacingException> { PathValidator.normalize("") }
        assertFailsWith<UserFacingException> { PathValidator.normalize("relative-folder") }
    }

    @Test
    fun `settings store persists quoted windows path characters`() {
        val root = createTempDirectory("image-processor-settings-")
        val settingsPath = root.resolve("settings.json")
        val expected = AppSettings("C:\\图片\\Processed \"Final\"")
        SettingsStore.save(expected, settingsPath)
        assertEquals(expected, SettingsStore.load(settingsPath))
        assertTrue(Files.readString(settingsPath).contains("\"schemaVersion\": 1"))
    }

    @Test
    fun `animated gif is rejected instead of silently using its first frame`() {
        val root = createTempDirectory("image-processor-animated-")
        val path = root.resolve("animated.gif")
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        FileImageOutputStream(path.toFile()).use { output ->
            writer.output = output
            writer.prepareWriteSequence(null)
            repeat(2) { index ->
                val frame = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
                val graphics = frame.createGraphics()
                graphics.color = if (index == 0) Color.RED else Color.BLUE
                graphics.fillRect(0, 0, 4, 4)
                graphics.dispose()
                writer.writeToSequence(IIOImage(frame, null, null), writer.defaultWriteParam)
            }
            writer.endWriteSequence()
        }
        writer.dispose()

        val error = assertFailsWith<UserFacingException> { ImageIOService.load(path) }
        assertTrue(error.message.orEmpty().contains("动态 GIF"))
    }

    @Test
    fun `content and extension mismatch is rejected`() {
        val root = createTempDirectory("image-processor-mismatch-")
        val path = root.resolve("wrong.jpg")
        val image = BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB)
        check(ImageIO.write(image, "png", path.toFile()))
        val error = assertFailsWith<UserFacingException> { ImageIOService.load(path) }
        assertTrue(error.message.orEmpty().contains("扩展名"))
    }

    private fun writeSamplePng(path: Path) {
        val image = BufferedImage(11, 9, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(30, 80, 180, 180)
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.color = Color.YELLOW
        graphics.fillRect(0, 0, 4, 3)
        graphics.dispose()
        check(ImageIO.write(image, "png", path.toFile()))
    }
}
