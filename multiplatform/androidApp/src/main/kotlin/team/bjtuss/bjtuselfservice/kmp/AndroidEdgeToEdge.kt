package team.bjtuss.bjtuselfservice.kmp

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal fun ComponentActivity.enableImmersiveEdgeToEdge() {
    enableEdgeToEdge(
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}

/** Reuse the current window's measured inset before the next Compose host gets its first dispatch. */
internal fun ComponentActivity.rememberSystemBottomInset() {
    val insets = ViewCompat.getRootWindowInsets(window.decorView) ?: return
    AndroidAuthenticatedSessionRegistry.session?.systemBottomInsetDp =
        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom / resources.displayMetrics.density
}
