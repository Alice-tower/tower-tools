package dev.towertools.proxyenvmanager

interface UserEnvironmentStore {
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun delete(name: String)
    fun broadcastChange()
}
