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
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider

private val WideSage = ColorProvider(Color(0xFF93A486))

/** The wide capture/reflection shortcut; it contains labels only, never vault state. */
class TwoWaysInWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: androidx.glance.GlanceId) {
        val reflectionAction = actionStartActivity(widgetIntent(context, REFLECTION_DEEP_LINK))
        val captureAction = actionStartActivity(widgetIntent(context, CAPTURE_DEEP_LINK))
        provideContent {
            TwoWaysInContent(reflectionAction, captureAction)
        }
    }
}

@Composable
private fun TwoWaysInContent(
    reflectionAction: androidx.glance.action.Action,
    captureAction: androidx.glance.action.Action,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(WideSage)
            .padding(16.dp),
    ) {
        Text("Respiral", modifier = GlanceModifier.padding(bottom = 8.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Button(
                text = "Remind me",
                onClick = reflectionAction,
                modifier = GlanceModifier.padding(end = 8.dp),
            )
            Button(
                text = "Add a good thing",
                onClick = captureAction,
            )
        }
    }
}

class TwoWaysInWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TwoWaysInWidget()
}
