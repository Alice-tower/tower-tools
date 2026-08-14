package dev.towertools.mediatranscriber

import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealEnvironmentAcceptanceTest {
    @Test fun installedFfmpegSupportsProbeExportNormalizeAndCancel() {
        val ffmpeg = ExecutableResolver.resolve("ffmpeg.exe", "") ?: return
        val ffprobe = ExecutableResolver.resolve("ffprobe.exe", "") ?: return
        val directory = Files.createTempDirectory("media-transcriber-real-")
        val runner = ExternalProcessRunner()
        try {
            fun run(vararg args: String) {
                val result = runner.run(ProcessRequest(listOf(ffmpeg.toString()) + args, directory))
                check(result.exitCode == 0) { result.stderr }
            }
            val single = directory.resolve("single.mkv")
            run("-hide_banner","-loglevel","error","-f","lavfi","-i","testsrc=size=320x180:rate=10:duration=2","-f","lavfi","-i","sine=frequency=440:duration=2","-map","0:v","-map","1:a","-c:v","mpeg4","-c:a","aac",single.toString())
            val multi = directory.resolve("multi.mkv")
            run("-hide_banner","-loglevel","error","-f","lavfi","-i","testsrc=size=320x180:rate=10:duration=2","-f","lavfi","-i","sine=frequency=440:duration=2","-f","lavfi","-i","sine=frequency=880:duration=2","-map","0:v","-map","1:a","-map","2:a","-c:v","mpeg4","-c:a","aac","-disposition:a:0","0","-disposition:a:1","default",multi.toString())
            val silent = directory.resolve("silent.mkv")
            run("-hide_banner","-loglevel","error","-f","lavfi","-i","testsrc=size=320x180:rate=10:duration=2","-c:v","mpeg4",silent.toString())
            val direct = directory.resolve("direct.m4a")
            run("-hide_banner","-loglevel","error","-f","lavfi","-i","sine=frequency=440:duration=2","-c:a","aac",direct.toString())

            val probe = MediaProbeService(runner)
            val cancellation = CancellationHandle()
            assertEquals(1, probe.probe(ffprobe, single, directory, cancellation).audioTracks.size)
            val multiInfo = probe.probe(ffprobe, multi, directory, cancellation)
            assertEquals(2, multiInfo.audioTracks.size); assertEquals(2, multiInfo.selectedAudio?.streamIndex)
            assertEquals(null, probe.probe(ffprobe, silent, directory, cancellation).selectedAudio)
            assertEquals(MediaKind.AUDIO, probe.probe(ffprobe, direct, directory, cancellation).kind)

            val service = FfmpegService(runner)
            val mp3 = directory.resolve("export.mp3")
            service.run(service.mp3Command(ffmpeg, single, 1, mp3), directory, 2_000, CancellationHandle()) {}
            assertTrue(Files.size(mp3) > 0)
            val wav = directory.resolve("whisper-input.wav")
            service.run(service.wavCommand(ffmpeg, multi, 2, wav), directory, 2_000, CancellationHandle()) {}
            val wavInfo = probe.probe(ffprobe, wav, directory, CancellationHandle())
            assertEquals(16_000, wavInfo.selectedAudio?.sampleRate); assertEquals(1, wavInfo.selectedAudio?.channels); assertEquals("pcm_s16le", wavInfo.selectedAudio?.codec)

            val longCancellation = CancellationHandle()
            val pool = Executors.newSingleThreadExecutor()
            val future = pool.submit<ProcessResult> { runner.run(ProcessRequest(listOf(ffmpeg.toString(),"-hide_banner","-re","-f","lavfi","-i","sine=frequency=440","-t","60","-f","null","NUL"), directory), longCancellation) }
            Thread.sleep(500); longCancellation.cancel()
            val cancelled = future.get(5, TimeUnit.SECONDS)
            assertTrue(cancelled.cancelled); assertFalse(future.isCancelled)
            pool.shutdownNow()
        } finally {
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
