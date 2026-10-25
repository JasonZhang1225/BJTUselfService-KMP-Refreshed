/*
 * macOS 的系统 libsqlite3 编译时去掉了扩展加载支持（SQLITE_OMIT_LOAD_EXTENSION），
 * 不导出 sqlite3_load_extension / sqlite3_enable_load_extension。
 * 而 SQLDelight 的原生驱动（co.touchlab 的 sqliter cinterop）按标准 sqlite3.h
 * 生成了这两个符号的调用桩，于是链接期会报 undefined symbol。
 *
 * 应用从不启用 SQLite 扩展加载，这里补两个不会被调用的空实现，
 * 避免为了一个用不到的 API 去额外打包一份 SQLite。
 */
#include <stddef.h>

int sqlite3_load_extension(void *db, const char *zFile, const char *zProc, char **pzErrMsg) {
    (void)db;
    (void)zFile;
    (void)zProc;
    if (pzErrMsg != NULL) {
        *pzErrMsg = NULL;
    }
    return 1; /* SQLITE_ERROR */
}

int sqlite3_enable_load_extension(void *db, int onoff) {
    (void)db;
    (void)onoff;
    return 1; /* SQLITE_ERROR */
}
