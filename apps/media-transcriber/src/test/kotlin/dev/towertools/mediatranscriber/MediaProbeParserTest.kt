package dev.towertools.mediatranscriber

import kotlin.test.*
import java.nio.file.Paths

class MediaProbeParserTest {
    private val parser = MediaProbeParser()
    private fun parse(streams: String) = parser.parse(Paths.get("sample.bin"), """{"streams":[$streams],"format":{"format_name":"matroska","duration":"10.5","size":"100"}}""")
    private fun video(index: Int = 0, attached: Int = 0) = """{"index":$index,"codec_type":"video","codec_name":"h264","width":1920,"height":1080,"disposition":{"attached_pic":$attached}}"""
    private fun audio(index: Int, default: Int = 0) = """{"index":$index,"codec_type":"audio","codec_name":"aac","channels":2,"sample_rate":"48000","disposition":{"default":$default}}"""

    @Test fun videoWithDefaultAudio() { val value=parse("${video()},${audio(1,1)}"); assertEquals(MediaKind.VIDEO,value.kind); assertEquals(1,value.audioTracks.size); assertEquals(1,value.selectedAudio?.streamIndex) }
    @Test fun choosesDefaultAmongMultipleTracks() { val value=parse("${video()},${audio(1)},${audio(3,1)}"); assertEquals(3,value.selectedAudio?.streamIndex); assertEquals(2,value.audioTracks.size) }
    @Test fun choosesLowestIndexWithoutDefault() { assertEquals(2,parse("${video()},${audio(4)},${audio(2)}").selectedAudio?.streamIndex) }
    @Test fun supportsVideoWithoutAudio() { val value=parse(video()); assertEquals(MediaKind.VIDEO,value.kind); assertNull(value.selectedAudio) }
    @Test fun supportsPlainAudio() { assertEquals(MediaKind.AUDIO,parse(audio(0)).kind) }
    @Test fun attachedCoverDoesNotMakeAudioVideo() { assertEquals(MediaKind.AUDIO,parse("${video(attached=1)},${audio(1)}").kind) }
}
