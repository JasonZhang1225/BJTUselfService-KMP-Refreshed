package team.bjtuss.bjtuselfservice.shared.cache

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql

private const val CACHE_DATABASE_FILE_NAME = "bjtuselfservice_cache.db"

/** Apple 平台原生共用：同一套 `NativeSqliteDriver` + Application Support 目录布局。 */
@OptIn(ExperimentalForeignApi::class)
fun createAppleCacheStore(): CacheStoreHandle {
    val fileManager = NSFileManager.defaultManager
    val directory = requireNotNull(
        fileManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )?.path,
    ) { "无法定位 Apple Application Support 目录。" }
    val possibleDatabasePaths = listOf(
        "$directory/databases/$CACHE_DATABASE_FILE_NAME",
        "$directory/$CACHE_DATABASE_FILE_NAME",
    )

    val handle = openCacheStoreWithRecovery(
        openDriver = {
            NativeSqliteDriver(
                schema = CacheDatabaseSql.Schema,
                name = CACHE_DATABASE_FILE_NAME,
            )
        },
        deleteStorage = {
            possibleDatabasePaths.forEach { databasePath ->
                listOf(databasePath, "$databasePath-wal", "$databasePath-shm").forEach { path ->
                    if (fileManager.fileExistsAtPath(path)) {
                        check(fileManager.removeItemAtPath(path, error = null)) {
                            "无法重建 Apple 本地缓存数据库。"
                        }
                    }
                }
            }
        },
    )
    // 缓存库含个人学业数据，不得随 iCloud/iTunes 备份上云。WAL/SHM 可能晚于本次
    // 启动才生成，因此每次启动都对已存在的文件重设排除位（尽力而为，不阻断启动）。
    possibleDatabasePaths.forEach { databasePath ->
        listOf(databasePath, "$databasePath-wal", "$databasePath-shm").forEach(::excludeFromBackup)
    }
    return handle
}

@OptIn(ExperimentalForeignApi::class)
private fun excludeFromBackup(path: String) {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return
    val url = NSURL.fileURLWithPath(path)
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        url.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = error.ptr)
    }
}
