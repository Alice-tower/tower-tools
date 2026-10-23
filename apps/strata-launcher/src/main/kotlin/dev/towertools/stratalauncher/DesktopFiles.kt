package dev.towertools.stratalauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.EventQueue
import java.nio.file.Path
import javax.swing.JFileChooser

object DesktopFiles {
    suspend fun chooseDirectory(owner: Component?, initial: String = ""): Path? = withContext(Dispatchers.IO) {
        var selected: Path? = null
        EventQueue.invokeAndWait {
            val chooser = JFileChooser().apply {
                dialogTitle = "选择目录"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                if (initial.isNotBlank()) runCatching {
                    val file = Path.of(initial).toFile()
                    if (file.isDirectory) currentDirectory = file else selectedFile = file
                }
            }
            if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                selected = chooser.selectedFile.toPath()
            }
        }
        selected
    }

}
