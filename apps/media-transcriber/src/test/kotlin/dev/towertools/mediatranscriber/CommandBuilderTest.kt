package dev.towertools.mediatranscriber

import kotlin.test.*
import java.nio.file.Paths

class CommandBuilderTest {
    private val fake = object : ProcessRunner { override fun run(request: ProcessRequest, cancellation: CancellationHandle, onStdoutLine: (String)->Unit, onStderrLine:(String)->Unit)=ProcessResult(0,"","",false) }
    @Test fun ffmpegKeepsPathsAsArgumentsAndMapsSelectedStream() {
        val cmd=FfmpegService(fake).mp3Command(Paths.get("C:/程序 文件/ffmpeg.exe"),Paths.get("D:/输入 & 文件/video.mp4"),3,Paths.get("D:/输出/audio.mp3"))
        assertEquals("D:/输入 & 文件/video.mp4",cmd[cmd.indexOf("-i")+1].replace('\\','/')); assertEquals("0:3",cmd[cmd.indexOf("-map")+1]); assertFalse(cmd.any { it.equals("cmd.exe",true)||it.equals("powershell",true) })
    }
    @Test fun whisperUsesAutoLanguageAndNeverTranslate() {
        val cmd=WhisperService(fake).command(Paths.get("C:/Whisper CUDA/whisper-cli.exe"),Paths.get("D:/模型/model.bin"),Paths.get("x.wav"),Paths.get("out"))
        assertEquals("auto",cmd[cmd.indexOf("--language")+1]); assertFalse("--translate" in cmd); assertEquals("D:/模型/model.bin",cmd[cmd.indexOf("--model")+1].replace('\\','/'))
    }
    @Test fun processRequestIsArgumentListNotShellCommand() { val request=ProcessRequest(listOf("tool.exe","a b","中文&()"),Paths.get(".")); assertEquals(3,request.command.size); assertEquals("a b",request.command[1]) }
}
