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
    require(System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        "桌面缓存工厂仅供 macOS 使用；Windows 请使用 DPAPI 缓存工厂。"
    }
    val key = loadOrCreateMacOsCacheKey()
    val protector = JvmAesCacheValueProtector(key.bytes).also { key.bytes.fill(0) }
    return openDesktopCacheStore(baseDirectory, protector, key.created)
}

/** Allows schema and encryption migration tests without touching the user's Keychain. */
internal fun openDesktopCacheStore(
    baseDirectory: File,
    protector: CacheValueProtector,
    cacheKeyCreated: Boolean = false,
): CacheStoreHandle {
    require(baseDirectory.isDirectory || baseDirectory.mkdirs()) {
        "无法创建本地缓存目录。"
    }
    val databaseFile = File(baseDirectory, CACHE_DATABASE_FILE_NAME)
    val encryptionMarker = File(baseDirectory, CACHE_ENCRYPTION_MARKER)
    val cacheFilesExist = desktopCacheFiles(databaseFile).any(File::exists)
    val resetNeeded = !protector.isIdentity && cacheFilesExist &&
        (!encryptionMarker.isFile || cacheKeyCreated)
    return openCacheStoreWithRecovery(
        openDriver = {
            check(!resetNeeded) { "本地加密材料与已有缓存不匹配，已保留原数据库。" }
            JdbcSqliteDriver(
                url = "jdbc:sqlite:${databaseFile.absolutePath}",
                properties = Properties(),
                schema = CacheDatabaseSql.Schema,
            )
        },
        temporaryDriver = {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CacheDatabaseSql.Schema.create(it) }
        },
        protector = protector,
    ).let { handle ->
        if (!protector.isIdentity && handle.state == CacheOpenState.OPENED) encryptionMarker.writeText("v1\n")
        handle
    }
}

private const val CACHE_ENCRYPTION_MARKER = ".cache-encryption-v1"

private fun desktopCacheFiles(databaseFile: File): List<File> =
    listOf(
        databaseFile,
        File("${databaseFile.absolutePath}-wal"),
        File("${databaseFile.absolutePath}-shm"),
    )
