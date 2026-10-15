package top.iwesley.lyn.music.platform

import top.iwesley.lyn.music.resources.*

import top.iwesley.lyn.music.core.model.UiText
import top.iwesley.lyn.music.core.model.uiText

import java.nio.file.Files
import java.nio.file.Path
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.LinuxNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.OsxNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.WindowsNativeDiscoveryStrategy

internal fun resolveDesktopVlcEffectivePath(
    manualPath: String?,
    autoDetectedPath: String?,
): String? {
    return manualPath?.trim()?.takeIf { it.isNotBlank() }
        ?: autoDetectedPath?.trim()?.takeIf { it.isNotBlank() }
}

internal fun findBundledDesktopVlcDirectory(
    osName: String = System.getProperty("os.name"),
    listEntries: (Path) -> List<String> = ::listDesktopVlcDirectoryEntries,
): Path? {
    val searchRoots = mutableListOf<Path>()

    // 1. Compose Desktop app resources dir (System property set by Compose launcher)
    val composeResourcesDir = System.getProperty("compose.application.resources.dir")
    if (!composeResourcesDir.isNullOrBlank()) {
        searchRoots.add(Path.of(composeResourcesDir).resolve("vlc"))
    }

    // 2. Packaged macOS app bundle: java.home is typically Contents/runtime/Contents/Home
    val javaHome = System.getProperty("java.home")
    if (!javaHome.isNullOrBlank()) {
        val javaHomePath = Path.of(javaHome)
        // LynMusic.app/Contents/runtime/Contents/Home -> LynMusic.app/Contents/Resources/vlc
        searchRoots.add(javaHomePath.resolve("../../../Resources/vlc").normalize())
        searchRoots.add(javaHomePath.resolve("../../Resources/vlc").normalize())
        // Windows/Linux runtime fallback: LynMusic/runtime -> LynMusic/vlc
        searchRoots.add(javaHomePath.resolve("../../vlc").normalize())
    }

    // 3. Current working directory / dev environment
    val userDir = Path.of(System.getProperty("user.dir"))
    searchRoots.add(userDir.resolve("package-resources").resolve("vlc"))
    searchRoots.add(userDir.resolve("composeApp").resolve("package-resources").resolve("vlc"))

    for (root in searchRoots) {
        if (!Files.isDirectory(root)) continue
        // Check root directly (e.g. if root contains libvlc.dylib)
        if (containsDesktopVlcLibraries(root, osName, listEntries)) {
            return root.toAbsolutePath().normalize()
        }
        // Check root/lib (e.g. if root/lib contains libvlc.dylib)
        val libSubdir = root.resolve("lib")
        if (Files.isDirectory(libSubdir) && containsDesktopVlcLibraries(libSubdir, osName, listEntries)) {
            return libSubdir.toAbsolutePath().normalize()
        }
    }
    return null
}

internal fun resolveDesktopVlcPluginsDirectory(libraryDirectory: Path): Path? {
    val candidatePlugins = listOf(
        libraryDirectory.resolve("plugins"),
        libraryDirectory.resolve("../plugins").normalize(),
        libraryDirectory.parent?.resolve("plugins"),
    )
    return candidatePlugins.firstOrNull { it != null && Files.isDirectory(it) }?.toAbsolutePath()?.normalize()
}

internal fun normalizeDesktopVlcSelection(
    selection: Path,
    osName: String = System.getProperty("os.name"),
    listEntries: (Path) -> List<String> = ::listDesktopVlcDirectoryEntries,
): Path? {
    val normalizedSelection = selection.toAbsolutePath().normalize()
    val candidate = when {
        isDesktopMacOs(osName) && normalizedSelection.fileName?.toString()?.endsWith(".app", ignoreCase = true) == true ->
            normalizedSelection.resolve("Contents").resolve("MacOS").resolve("lib")

        containsDesktopVlcLibraries(normalizedSelection, osName, listEntries) ->
            normalizedSelection

        containsDesktopVlcLibraries(normalizedSelection.resolve("lib"), osName, listEntries) ->
            normalizedSelection.resolve("lib")

        else -> normalizedSelection
    }
    return candidate.takeIf { containsDesktopVlcLibraries(it, osName, listEntries) }
}

internal fun desktopVlcInvalidSelectionMessage(osName: String = System.getProperty("os.name")): UiText {
    return if (isDesktopMacOs(osName)) {
        uiText(Res.string.desktop_vlc_selection_macos)
    } else {
        uiText(Res.string.desktop_vlc_selection)
    }
}

internal fun createDesktopVlcDiscovery(manualPath: String?): NativeDiscovery {
    val normalizedManualPath = manualPath?.trim()?.takeIf { it.isNotBlank() } ?: return NativeDiscovery()
    val delegate = desktopVlcDiscoveryStrategy() ?: return NativeDiscovery()
    return NativeDiscovery(FixedPathNativeDiscoveryStrategy(normalizedManualPath, delegate))
}

private fun containsDesktopVlcLibraries(
    directory: Path,
    osName: String,
    listEntries: (Path) -> List<String>,
): Boolean {
    val entries = listEntries(directory)
    return when {
        isDesktopMacOs(osName) -> {
            entries.any { it.equals("libvlc.dylib", ignoreCase = true) } &&
                entries.any { it.equals("libvlccore.dylib", ignoreCase = true) }
        }

        isDesktopWindows(osName) -> {
            entries.any { it.equals("libvlc.dll", ignoreCase = true) } &&
                entries.any { it.equals("libvlccore.dll", ignoreCase = true) }
        }

        else -> {
            entries.any { it.startsWith("libvlc.so", ignoreCase = true) } &&
                entries.any { it.startsWith("libvlccore.so", ignoreCase = true) }
        }
    }
}

private fun listDesktopVlcDirectoryEntries(directory: Path): List<String> {
    return runCatching {
        if (!Files.isDirectory(directory)) {
            emptyList()
        } else {
            Files.newDirectoryStream(directory).use { stream ->
                stream.map { it.fileName?.toString().orEmpty() }.toList()
            }
        }
    }.getOrDefault(emptyList())
}

private fun desktopVlcDiscoveryStrategy(osName: String = System.getProperty("os.name")): NativeDiscoveryStrategy? {
    return when {
        isDesktopMacOs(osName) -> OsxNativeDiscoveryStrategy()
        isDesktopWindows(osName) -> WindowsNativeDiscoveryStrategy()
        else -> LinuxNativeDiscoveryStrategy()
    }
}

private fun isDesktopMacOs(osName: String): Boolean {
    return osName.contains("mac", ignoreCase = true)
}

private fun isDesktopWindows(osName: String): Boolean {
    return osName.contains("win", ignoreCase = true)
}

private class FixedPathNativeDiscoveryStrategy(
    private val nativeLibraryPath: String,
    private val delegate: NativeDiscoveryStrategy,
) : NativeDiscoveryStrategy {
    override fun supported(): Boolean = delegate.supported()

    override fun discover(): String = nativeLibraryPath

    override fun onFound(path: String): Boolean = delegate.onFound(path)

    override fun onSetPluginPath(path: String): Boolean {
        val pluginsDir = resolveDesktopVlcPluginsDirectory(Path.of(path))
        if (pluginsDir != null) {
            runCatching {
                uk.co.caprica.vlcj.binding.lib.LibC.INSTANCE.setenv(
                    "VLC_PLUGIN_PATH",
                    pluginsDir.toString(),
                    1,
                )
            }
        }
        return delegate.onSetPluginPath(path)
    }
}
