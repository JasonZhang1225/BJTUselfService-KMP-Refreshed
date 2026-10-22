package team.bjtuss.bjtuselfservice.shared.cache

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql

private const val CACHE_DATABASE_NAME = "bjtuselfservice_cache.db"

fun createAndroidCacheStore(context: Context): CacheStoreHandle {
    val appContext = context.applicationContext
    return openCacheStoreWithRecovery(
        openDriver = {
            AndroidSqliteDriver(
                schema = CacheDatabaseSql.Schema,
                context = appContext,
                name = CACHE_DATABASE_NAME,
                callback = object : AndroidSqliteDriver.Callback(CacheDatabaseSql.Schema) {
                    override fun onCorruption(db: SupportSQLiteDatabase) {
                        // AndroidX 默认处理会删除库文件，改为交给共享的保留数据错误页。
                        throw SQLiteException("本地数据库暂时不可用，已保留原文件。")
                    }
                },
            )
        },
        temporaryDriver = { AndroidSqliteDriver(CacheDatabaseSql.Schema, appContext, null) },
    )
}
