package team.bjtuss.bjtuselfservice.windows

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties
import team.bjtuss.bjtuselfservice.shared.cache.CacheStoreHandle
import team.bjtuss.bjtuselfservice.shared.cache.CacheOpenState
import team.bjtuss.bjtuselfservice.shared.cache.JvmAesCacheValueProtector
import team.bjtuss.bjtuselfservice.shared.cache.openCacheStoreWithRecovery
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql

private const val CACHE_DATABASE_FILE_NAME = "bjtuselfservice_cache.db"

fun createWindowsCacheStore(
    baseDirectory: File = File(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
        "BJTUselfServiceKMP",
    ),
): CacheStoreHandle {
    require(baseDirectory.isDirectory || baseDirectory.mkdirs()) {
        "无法创建本地缓存目录。"
    }
    val databaseFile = File(baseDirectory, CACHE_DATABASE_FILE_NAME)
    val encryptionMarker = File(baseDirectory, CACHE_ENCRYPTION_MARKER)
    val key = loadOrCreateWindowsCacheKey()
    val cacheFilesExist = windowsCacheFiles(databaseFile).any(File::exists)
    val resetNeeded = cacheFilesExist && (!encryptionMarker.isFile || key.created)
    if (resetNeeded) deleteWindowsCacheFiles(databaseFile)
    val protector = JvmAesCacheValueProtector(key.bytes).also { key.bytes.fill(0) }
    return openCacheStoreWithRecovery(
        openDriver = {
            JdbcSqliteDriver(
                url = "jdbc:sqlite:${databaseFile.absolutePath}",
                properties = Properties(),
                schema = CacheDatabaseSql.Schema,
            )
        },
        deleteStorage = {
            deleteWindowsCacheFiles(databaseFile)
        },
        protector = protector,
    ).let { handle ->
        encryptionMarker.writeText("v1\n")
        if (resetNeeded) handle.copy(state = CacheOpenState.MIGRATED_TO_ENCRYPTED) else handle
    }
}

private const val CACHE_ENCRYPTION_MARKER = ".cache-encryption-v1"

private fun deleteWindowsCacheFiles(databaseFile: File) {
    windowsCacheFiles(databaseFile).forEach { file ->
        if (file.exists() && !file.delete()) error("无法重建本地缓存数据库。")
    }
}

private fun windowsCacheFiles(databaseFile: File): List<File> =
    listOf(
        databaseFile,
        File("${databaseFile.absolutePath}-wal"),
        File("${databaseFile.absolutePath}-shm"),
    )
