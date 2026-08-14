package dev.towertools.mediatranscriber

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WhisperServiceTest {
    private val runner = object : ProcessRunner {
        override fun run(request: ProcessRequest, cancellation: CancellationHandle, onStdoutLine: (String) -> Unit, onStderrLine: (String) -> Unit) = ProcessResult(0, "", "", false)
    }
    private val service = WhisperService(runner)

    @Test fun parsesLanguageOffsetsAndProgress() {
        val result = service.parseJson("""{"result":{"language":"zh"},"transcription":[{"text":" 你好 ","offsets":{"from":60000}}]}""")
        assertEquals("zh", result.language); assertEquals(60_000, result.segments.single().startMilliseconds)
        assertEquals(42, service.parseProgress("whisper_print_progress: progress = 42%"))
        assertEquals(null, service.parseProgress("42 percent"))
    }

    @Test fun realTranscriptionRequiresAValidSegmentButModelCheckMayBeSilent() {
        assertFailsWith<IllegalStateException> { service.parseJson("""{"transcription":[]}""") }
        assertEquals(emptyList(), service.parseJson("""{"transcription":[]}""", requireSegments = false).segments)
    }
}
