package team.bjtuss.bjtuselfservice.kmp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.AuthenticatedDestinationApp
import team.bjtuss.bjtuselfservice.shared.isNativeDetailRoute
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeHostState

class NativeDetailActivity : ComponentActivity() {
    private val nativeHostState = NativeHostState()
    private val refreshRate = AndroidRefreshRateController(this)
    private var removeSessionObserver: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveEdgeToEdge()
        refreshRate.start()
        val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
        nativeHostState.systemBottomInsetDp = intent.getFloatExtra(EXTRA_SYSTEM_BOTTOM_INSET, 0f)
        val session = AndroidAuthenticatedSessionRegistry.session
        if (routeId == null || !isNativeDetailRoute(routeId) || session == null) {
            if (BuildConfig.DEBUG) Log.d("NativeNavigation", "Detail rejected routeValid=${routeId != null && isNativeDetailRoute(routeId)} sessionReady=${session != null}")
            finish()
            return
        }
        if (BuildConfig.DEBUG) Log.d("NativeNavigation", "Detail opened route=$routeId")

        val fileGateway = AndroidFileGateway(this)
        setContent {
            AuthenticatedDestinationApp(
                session = session,
                routeId = routeId,
                nativeHostState = nativeHostState,
                fileGateway = fileGateway,
                directoryGateway = fileGateway,
                onOpenExternalUrl = ::openExternalUrl,
                onOpenNativeRoute = ::openRoute,
                onCloseNativeRoute = ::finish,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        refreshRate.apply()
        removeSessionObserver = AndroidAuthenticatedSessionRegistry.observe { session ->
            if (session == null && !isFinishing) finish()
        }
    }

    override fun onResume() {
        super.onResume()
        AndroidAuthenticatedSessionRegistry.notifyAppBecameActive()
        refreshRate.apply()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshRate.apply()
    }

    override fun onStop() {
        removeSessionObserver?.invoke()
        removeSessionObserver = null
        super.onStop()
    }

    override fun onDestroy() {
        refreshRate.stop()
        if (isFinishing && intent.getStringExtra(EXTRA_ROUTE_ID) == MAILBOX_COMPOSE_ROUTE_ID) {
            AndroidAuthenticatedSessionRegistry.session?.mailboxModel?.let { mailboxModel ->
                lifecycleScope.launch { mailboxModel.cancelCompose() }
            }
        }
        super.onDestroy()
    }

    private fun openRoute(routeId: String) {
        if (!isNativeDetailRoute(routeId)) return
        startActivity(intentFor(this, routeId))
    }

    private fun openExternalUrl(url: String) {
        openHttpsUrl(this, url)
    }

    companion object {
        private const val EXTRA_SYSTEM_BOTTOM_INSET = "native_system_bottom_inset"
        private const val EXTRA_ROUTE_ID = "native_route_id"
        private const val MAILBOX_COMPOSE_ROUTE_ID = "MAILBOX_COMPOSE"

        fun intentFor(activity: ComponentActivity, routeId: String): Intent =
            Intent(activity, NativeDetailActivity::class.java)
                .putExtra(EXTRA_ROUTE_ID, routeId)
                .putExtra(EXTRA_SYSTEM_BOTTOM_INSET, activity.measuredSystemBottomInsetDp())
    }
}
