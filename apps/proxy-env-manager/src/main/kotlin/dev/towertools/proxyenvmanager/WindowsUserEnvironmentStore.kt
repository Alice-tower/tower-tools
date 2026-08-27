package dev.towertools.proxyenvmanager

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

object WindowsUserEnvironmentStore : UserEnvironmentStore {
    private const val ENVIRONMENT_KEY = "Environment"

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
}
