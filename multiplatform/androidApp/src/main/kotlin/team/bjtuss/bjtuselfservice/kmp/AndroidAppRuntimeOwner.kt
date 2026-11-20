package team.bjtuss.bjtuselfservice.kmp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.cache.createAndroidCacheStore
import team.bjtuss.bjtuselfservice.shared.runtime.AppRuntime
import team.bjtuss.bjtuselfservice.shared.security.createAndroidAccountSecurityStore
import team.bjtuss.bjtuselfservice.kmp.playback.AndroidPlaybackRecovery

/** Retained across Activity configuration changes; never retains an Activity or its launchers. */
class AndroidAppRuntimeOwner(application: Application) : AndroidViewModel(application) {
    val securityStore = createAndroidAccountSecurityStore(application)
    val cacheStore = createAndroidCacheStore(application)
    val runtime = AppRuntime()
    val playbackRecovery = AndroidPlaybackRecovery(application)
    private val publication = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        publication.launch { runtime.session.collect(::publish) }
    }

    fun publish(session: AuthenticatedSession?) {
        if (runtime.session.value === session) AndroidAuthenticatedSessionRegistry.update(this, session)
    }

    override fun onCleared() {
        AndroidAuthenticatedSessionRegistry.update(this, null)
        publication.cancel()
        runtime.close { cacheStore.store.close() }
    }
}
