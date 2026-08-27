package app.respiral.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.Button
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider

private val QuietPaper = ColorProvider(Color(0xFFEAD9B8))

/** A deliberately content-free one-tap return to the reflection scene. */
class QuietReturnWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: androidx.glance.GlanceId) {
        val reflectionAction = actionStartActivity(widgetIntent(context, REFLECTION_DEEP_LINK))
        provideContent {
            QuietReturnContent(reflectionAction)
        }
    }
}

@Composable
private fun QuietReturnContent(reflectionAction: androidx.glance.action.Action) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(QuietPaper)
            .padding(16.dp),
    ) {
        Text("☼", modifier = GlanceModifier.padding(bottom = 4.dp))
        Text("Respiral", modifier = GlanceModifier.padding(bottom = 8.dp))
        Button(
            text = "Remind me",
            onClick = reflectionAction,
            modifier = GlanceModifier.fillMaxSize(),
        )
    }
}

class QuietReturnWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuietReturnWidget()
}
