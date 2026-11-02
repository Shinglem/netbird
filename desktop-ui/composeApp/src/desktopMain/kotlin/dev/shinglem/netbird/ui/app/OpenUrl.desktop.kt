package dev.shinglem.netbird.ui.app

import java.awt.Desktop
import java.net.URI

actual fun openUrl(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return false
    Desktop.getDesktop().browse(URI(url))
    true
}.getOrDefault(false)
