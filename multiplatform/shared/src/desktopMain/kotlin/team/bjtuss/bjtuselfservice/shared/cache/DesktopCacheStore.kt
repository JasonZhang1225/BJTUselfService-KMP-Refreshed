package team.bjtuss.bjtuselfservice.shared.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql

private const val CACHE_DATABASE_FILE_NAME = "bjtuselfservice_cache.db"

fun createDesktopCacheStore(
    baseDirectory: File = File(
        System.getProperty("user.home"),
        "Library/Application Support/BJTUselfServiceKMP",
    ),
): CacheStoreHandle {
    require(baseDirectory.isDirectory || baseDirectory.mkdirs()) {
        "无法创建本地缓存目录。"
    }
    val databaseFile = File(baseDirectory, CACHE_DATABASE_FILE_NAME)
    val encryptionMarker = File(baseDirectory, CACHE_ENCRYPTION_MARKER)
    val key = if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        loadOrCreateMacOsCacheKey()
    } else {
        // desktopMain tests also run on Windows; the production Windows app has
        // its own DPAPI-backed factory below and never takes this branch.
        null
    }
    val cacheFilesExist = desktopCacheFiles(databaseFile).any(File::exists)
    val resetNeeded = key != null && cacheFilesExist && (!encryptionMarker.isFile || key.created)
    if (resetNeeded) {
        deleteDesktopCacheFiles(databaseFile)
    }
    val protector = key?.let {
        JvmAesCacheValueProtector(it.bytes).also { _ -> it.bytes.fill(0) }
    } ?: PlaintextCacheValueProtector
    return openCacheStoreWithRecovery(
        openDriver = {
            JdbcSqliteDriver(
                url = "jdbc:sqlite:${databaseFile.absolutePath}",
                properties = Properties(),
                schema = CacheDatabaseSql.Schema,
            )
        },
        deleteStorage = {
            deleteDesktopCacheFiles(databaseFile)
        },
        protector = protector,
    ).let { handle ->
        if (key != null) encryptionMarker.writeText("v1\n")
        if (resetNeeded) handle.copy(state = CacheOpenState.MIGRATED_TO_ENCRYPTED) else handle
    }
}

private const val CACHE_ENCRYPTION_MARKER = ".cache-encryption-v1"

private fun deleteDesktopCacheFiles(databaseFile: File) {
    desktopCacheFiles(databaseFile).forEach { file ->
        if (file.exists() && !file.delete()) error("无法重建本地缓存数据库。")
    }
}

private fun desktopCacheFiles(databaseFile: File): List<File> =
    listOf(
        databaseFile,
        File("${databaseFile.absolutePath}-wal"),
        File("${databaseFile.absolutePath}-shm"),
    )
