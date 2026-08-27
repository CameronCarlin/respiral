package app.respiral

import android.app.KeyguardManager
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentActivity
import app.respiral.ui.AppNavGraph
import app.respiral.ui.theme.RespiralTheme
import app.respiral.widget.parseAppRoute
import app.respiral.security.DeviceLockVaultSessionObserver

class MainActivity : FragmentActivity() {
    private var appRoute by mutableStateOf<String?>(null)
    private val applicationRoot: RespiralApplication
        get() = RespiralApplication.from(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(
            DeviceLockVaultSessionObserver(
                getSystemService(KeyguardManager::class.java),
                applicationRoot.vaultSession,
            ),
        )
        enableEdgeToEdge()
        appRoute = parseAppRoute(intent?.data)

        setContentView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    RespiralTheme {
                        AppNavGraph(initialRoute = appRoute)
                    }
                }
            },
        )
    }

    override fun onStop() {
        super.onStop()
        if (getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) {
            applicationRoot.vaultSession.lock()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appRoute = parseAppRoute(intent.data)
    }
}
