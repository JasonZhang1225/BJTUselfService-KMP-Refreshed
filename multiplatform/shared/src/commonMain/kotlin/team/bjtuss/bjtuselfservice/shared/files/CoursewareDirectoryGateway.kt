package team.bjtuss.bjtuselfservice.shared.files

import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent

private const val MAX_EXPORT_NAME_CHARS = 80
private const val MAX_PRESERVED_EXTENSION_CHARS = 16

/** Removes path semantics and invisible controls before a server/user supplied name reaches a platform file API. */
fun safeExportFileName(value: String): String {
    val leaf = value.substringAfterLast('/').substringAfterLast('\\')
    val safe = leaf.sanitizeExportName(fallback = "attachment.bin")
    if (safe.length <= MAX_EXPORT_NAME_CHARS) return safe
    val extensionStart = safe.lastIndexOf('.')
    val extension = if (extensionStart in 1 until safe.lastIndex) {
        safe.substring(extensionStart).takeIf { it.length <= MAX_PRESERVED_EXTENSION_CHARS }.orEmpty()
    } else {
        ""
    }
    return if (extension.isEmpty()) {
        safe.takeExportPrefix(MAX_EXPORT_NAME_CHARS)
    } else {
        safe.takeExportPrefix(MAX_EXPORT_NAME_CHARS - extension.length) + extension
    }
}

/** Converts one logical directory label into a single, bounded platform path component. */
fun safeExportPathSegment(value: String): String = value
    .replace('/', '_')
    .replace('\\', '_')
    .sanitizeExportName(fallback = "courseware")
    .takeExportPrefix(MAX_EXPORT_NAME_CHARS)

private fun String.sanitizeExportName(fallback: String): String {
    val sanitized = buildString {
        this@sanitizeExportName.trim().forEach { character ->
            append(if (character.isUnsafeExportCharacter()) '_' else character)
        }
    }.trim().trimEnd('.')
    return sanitized.takeUnless { it.isBlank() || it == "." || it == ".." } ?: fallback
}

private fun Char.isUnsafeExportCharacter(): Boolean =
    code < 0x20 ||
        code == 0x7f ||
        code in 0x202a..0x202e ||
        code in 0x2066..0x2069

/** Keeps a UTF-16 limit from leaving an unmatched high surrogate at the end of a platform path component. */
private fun String.takeExportPrefix(maxChars: Int): String {
    val prefix = take(maxChars)
    return if (prefix.lastOrNull()?.code in 0xd800..0xdbff) prefix.dropLast(1) else prefix
}

data class CoursewareDirectoryFile(
    val relativeFolders: List<String>,
    val content: HomeworkFileContent,
) {
    init {
        require(relativeFolders.none(String::isBlank))
    }

    override fun toString(): String =
        "CoursewareDirectoryFile(depth=${relativeFolders.size}, content=<redacted>)"
}

/** Allocates one deterministic, case-insensitive export tree before platform providers see the names. */
class CoursewareExportNameAllocator {
    private val resolvedFolders = mutableMapOf<List<String>, List<String>>(emptyList<String>() to emptyList())
    private val usedNamesByFolder = mutableMapOf<List<String>, MutableSet<String>>()

    fun resolve(file: CoursewareDirectoryFile): CoursewareDirectoryFile {
        var sourcePath = emptyList<String>()
        var exportPath = emptyList<String>()
        file.relativeFolders.forEach { sourceSegment ->
            sourcePath = sourcePath + sourceSegment
            val existing = resolvedFolders[sourcePath]
            if (existing != null) {
                exportPath = existing
            } else {
                val exportSegment = allocateUniqueName(
                    baseName = safeExportPathSegment(sourceSegment),
                    usedNames = usedNames(exportPath),
                    preserveExtension = false,
                )
                exportPath = exportPath + exportSegment
                resolvedFolders[sourcePath] = exportPath
            }
        }
        val exportFileName = allocateUniqueName(
            baseName = safeExportFileName(file.content.fileName),
            usedNames = usedNames(exportPath),
            preserveExtension = true,
        )
        return CoursewareDirectoryFile(
            relativeFolders = exportPath,
            content = HomeworkFileContent(
                fileName = exportFileName,
                contentType = file.content.contentType,
                bytes = file.content.bytes,
            ),
        )
    }

    private fun usedNames(folder: List<String>): MutableSet<String> =
        usedNamesByFolder.getOrPut(folder) { mutableSetOf() }
}

private fun allocateUniqueName(
    baseName: String,
    usedNames: MutableSet<String>,
    preserveExtension: Boolean,
): String {
    var candidate = baseName
    var ordinal = 2
    while (!usedNames.add(candidate.lowercase())) {
        candidate = baseName.withExportSuffix(" ($ordinal)", preserveExtension)
        ordinal++
    }
    return candidate
}

private fun String.withExportSuffix(suffix: String, preserveExtension: Boolean): String {
    val extensionStart = if (preserveExtension) lastIndexOf('.') else -1
    val extension = if (extensionStart in 1 until lastIndex) {
        substring(extensionStart).takeIf { it.length <= MAX_PRESERVED_EXTENSION_CHARS }.orEmpty()
    } else {
        ""
    }
    val stem = if (extension.isEmpty()) this else substring(0, extensionStart)
    val stemLimit = (MAX_EXPORT_NAME_CHARS - suffix.length - extension.length).coerceAtLeast(1)
    return stem.takeExportPrefix(stemLimit) + suffix + extension
}

sealed interface CoursewareDirectoryOpenResult {
    class Opened(val session: CoursewareDirectoryWriteSession) : CoursewareDirectoryOpenResult {
        override fun toString(): String = "Opened(session=<redacted>)"
    }

    data object Cancelled : CoursewareDirectoryOpenResult
    data class Failed(val reason: HomeworkFileGatewayFailure) : CoursewareDirectoryOpenResult
}

/** A newly created export root. [abort] must be idempotent and remove only that new root. */
interface CoursewareDirectoryWriteSession {
    suspend fun write(file: CoursewareDirectoryFile): HomeworkFileSaveResult
    val supportsStreamingFiles: Boolean get() = false
    suspend fun openFile(file: CoursewareDirectoryFile): StreamingDestinationResult =
        StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
    suspend fun commit(): HomeworkFileSaveResult
    suspend fun abort()
}

/** 平台实现必须先让用户选择位置并创建新根目录，再通过会话逐文件写入。 */
interface CoursewareDirectoryGateway {
    val isDirectoryExportAvailable: Boolean
    suspend fun openDirectory(directoryName: String): CoursewareDirectoryOpenResult
}

object UnavailableCoursewareDirectoryGateway : CoursewareDirectoryGateway {
    override val isDirectoryExportAvailable: Boolean = false

    override suspend fun openDirectory(directoryName: String): CoursewareDirectoryOpenResult =
        CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
}
