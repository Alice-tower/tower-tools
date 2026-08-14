package dev.towertools.mediatranscriber

import kotlin.test.*
import java.nio.file.Paths

class ControllerStateTest {
    private fun media(kind: MediaKind, audio: Boolean)=MediaInfo(Paths.get("x"),kind,"fmt",1000,1,audioTracks=if(audio) listOf(AudioTrack(1)) else emptyList(),selectedAudio=if(audio) AudioTrack(1) else null)
    @Test fun videoAudioAndNoAudioTransitions() { val m=ControllerStateMachine(); m.beginProbe(); m.mediaReady(media(MediaKind.VIDEO,true)); assertEquals(TaskPhase.READY_VIDEO,m.state.phase); m.beginProbe(); m.mediaReady(media(MediaKind.AUDIO,true)); assertEquals(TaskPhase.READY_AUDIO,m.state.phase); m.beginProbe(); m.mediaReady(media(MediaKind.VIDEO,false)); assertEquals(TaskPhase.NO_AUDIO,m.state.phase) }
    @Test fun refusesNewFileWhileBusyAndRestoresReadyAfterCancel() { val m=ControllerStateMachine(); m.mediaReady(media(MediaKind.VIDEO,true)); m.taskStarted(TaskPhase.EXTRACTING_MP3); assertFalse(m.beginProbe()); m.cancelComplete(); assertEquals(TaskPhase.READY_VIDEO,m.state.phase) }
    @Test fun probingShowsCorrectStatusAndCanBeCancelled() { val m=ControllerStateMachine(); assertTrue(m.beginProbe()); assertEquals("正在读取媒体信息", m.state.progressText); assertTrue(m.state.canCancel); m.cancelComplete(); assertEquals(TaskPhase.IDLE, m.state.phase) }
    @Test fun cudaFailureOnlyDisablesTranscription() { val deps=DependencyStatus(mediaAvailable=true,cudaAvailable=false,modelExists=true,outputAvailable=true); val m=ControllerStateMachine(AppState(media=media(MediaKind.VIDEO,true),phase=TaskPhase.READY_VIDEO,dependencies=deps)); assertTrue(m.state.canExportMp3); assertFalse(m.state.canTranscribe) }
}
