package dev.towertools.stratalauncher

data class LaunchConfig(
    val action: String = "launch",
    val family: String = "unsloth",
    val model: String = "ud-iq4_xs",
    val preset: String = "",
    val dataDir: String = "",
    val context: String = "128k",
    val kv: String = "int8",
    val vision: String = "off",
    val parallel: String = "1",
    val gpu: String = "",
    val tune: String = "skip",
    val access: String = "local",
    val port: String = "7271",
    val keyMode: String = "none",
    val apiKey: String = "",
    val savePreset: String = "",
    val vramReserve: String = "2048",
)

data class ManagementConfig(
    val action: String = "status",
    val scope: String = "preset",
    val preset: String = "",
)
