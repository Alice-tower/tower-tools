package dev.towertools.mediatranscriber

import kotlin.test.*
import java.nio.file.Files

class SettingsStoreTest {
    @Test fun savesReadsAllFieldsAndReplacesTemporaryFile() {
        val dir=Files.createTempDirectory("settings"); val file=dir.resolve("settings.json"); val store=SettingsStore(file)
        val expected=AppSettings(1,"D:/输出","C:/ffmpeg","C:/whisper.exe","D:/model.bin",true)
        store.save(expected); assertEquals(expected,store.load()); assertFalse(Files.exists(store.temporaryFile()))
        store.save(expected.copy(includeTimestamps=false)); assertFalse(store.load().includeTimestamps)
    }
    @Test fun corruptJsonFallsBackAndWarns() { val file=Files.createTempFile("bad-settings",".json"); Files.writeString(file,"{"); var warned=false; val value=SettingsStore(file){warned=true}.load(); assertEquals(AppSettings(),value); assertTrue(warned) }
}
