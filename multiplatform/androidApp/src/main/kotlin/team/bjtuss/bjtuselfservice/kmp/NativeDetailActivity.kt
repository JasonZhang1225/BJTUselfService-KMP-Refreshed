package team.bjtuss.bjtuselfservice.kmp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.AuthenticatedDestinationApp
import team.bjtuss.bjtuselfservice.shared.isNativeDetailRoute

class NativeDetailActivity : ComponentActivity() {
    private val refreshRate = AndroidRefreshRateController(this)
    private var removeSessionObserver: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshRate.start()
        val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
        val session = AndroidAuthenticatedSessionRegistry.session
        if (routeId == null || !isNativeDetailRoute(routeId) || session == null) {
            finish()
            return
        }

        val fileGateway = AndroidHomeworkFileGateway(this)
        setContent {
            AuthenticatedDestinationApp(
                session = session,
                routeId = routeId,
                homeworkFileGateway = fileGateway,
                coursewareDirectoryGateway = fileGateway,
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
        if (!url.startsWith("https://")) return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    companion object {
        private const val EXTRA_ROUTE_ID = "native_route_id"
        private const val MAILBOX_COMPOSE_ROUTE_ID = "MAILBOX_COMPOSE"

        fun intentFor(activity: ComponentActivity, routeId: String): Intent =
            Intent(activity, NativeDetailActivity::class.java).putExtra(EXTRA_ROUTE_ID, routeId)
    }
}

