package app.respiral

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

class MainActivity : FragmentActivity() {
    private var appRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appRoute = parseAppRoute(intent.data)
    }
}
