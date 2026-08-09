package dev.towertools.proxyenvmanager

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

object WindowsUserEnvironmentStore : UserEnvironmentStore {
    private const val ENVIRONMENT_KEY = "Environment"
    private const val WM_SETTINGCHANGE = 0x001A
    private const val SMTO_ABORTIFHUNG = 0x0002

    override fun read(name: String): String? {
        if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, ENVIRONMENT_KEY, name)) {
            return null
        }
        return Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, ENVIRONMENT_KEY, name)
    }

    override fun write(name: String, value: String) {
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, ENVIRONMENT_KEY, name, value)
    }

    override fun delete(name: String) {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, ENVIRONMENT_KEY, name)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, ENVIRONMENT_KEY, name)
        }
    }

    override fun broadcastChange() {
        val message = Memory((("Environment".length + 1) * Native.WCHAR_SIZE).toLong())
        message.setWideString(0, "Environment")
        val result = BaseTSD.ULONG_PTRByReference()
        EnvironmentUser32.INSTANCE.SendMessageTimeoutW(
            WinUser.HWND_BROADCAST,
            WM_SETTINGCHANGE,
            WinDef.WPARAM(0),
            WinDef.LPARAM(Pointer.nativeValue(message)),
            SMTO_ABORTIFHUNG,
            5_000,
            result,
        )
    }

    private interface EnvironmentUser32 : StdCallLibrary {
        fun SendMessageTimeoutW(
            window: WinDef.HWND,
            message: Int,
            wordParameter: WinDef.WPARAM,
            longParameter: WinDef.LPARAM,
            flags: Int,
            timeoutMilliseconds: Int,
            result: BaseTSD.ULONG_PTRByReference,
        ): WinDef.LRESULT

        companion object {
            val INSTANCE: EnvironmentUser32 = Native.load(
                "user32",
                EnvironmentUser32::class.java,
                W32APIOptions.UNICODE_OPTIONS,
            )
        }
    }
}
