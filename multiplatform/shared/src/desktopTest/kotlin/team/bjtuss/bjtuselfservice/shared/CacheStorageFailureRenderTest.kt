package team.bjtuss.bjtuselfservice.shared

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import team.bjtuss.bjtuselfservice.shared.cache.*
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.security.*

class CacheStorageFailureRenderTest {
    @Test fun unavailableStorageStopsBeforeAccountOrPreferenceAccess() {
        SwingUtilities.invokeAndWait {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            CacheDatabaseSql.Schema.create(driver)
            val cache = CacheStore(driver)
            val security = AccountSecurityStore(null, object : AccountPreferences {
                override suspend fun shouldRememberCredentials(): Boolean = error("Must not access account settings")
                override suspend fun setShouldRememberCredentials(enabled: Boolean) = error("Must not write account settings")
                override suspend fun clearRememberCredentialsSetting() = error("Must not clear account settings")
            })
            try {
                for (variant in listOf("startup", "light", "dark", "large")) {
                    val scene = ImageComposeScene(width = 390, height = 844,
                        density = Density(1f, if (variant == "large") 2f else 1f),
                        coroutineContext = Dispatchers.Unconfined) {
                        if (variant == "startup") App(security, CacheStoreHandle(cache, CacheOpenState.UNAVAILABLE))
                        else CacheStorageFailureScreen(useDarkTheme = variant == "dark")
                    }
                    try {
                        repeat(3) { scene.render() }
                        val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
                        val output = File("../../.artifacts/cache-storage-failure/$variant.png")
                        output.parentFile.mkdirs()
                        output.writeBytes(bytes)
                        assertTrue(bytes.size > 1000)
                        assertEquals(0L, cache.rowCount(), "Error screen must not initialize preferences")
                    } finally { scene.close() }
                }
            } finally { cache.close() }
        }
    }
}
