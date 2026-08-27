package app.respiral.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.respiral.MainActivity

const val REFLECTION_DEEP_LINK: String = "respiral://reflection"
const val CAPTURE_DEEP_LINK: String = "respiral://capture"

/** Maps the small, private set of app links used by widgets to navigation destinations. */
fun parseAppRoute(uri: Uri?): String? {
    val appUri = uri ?: return null
    if (!appUri.scheme.equals("respiral", ignoreCase = true)) return null
    return when (appUri.host?.lowercase()) {
        "reflection" -> "reflection"
        "capture" -> "editor"
        else -> null
    }
}

internal fun widgetIntent(context: Context, link: String): Intent = Intent(
    context,
    MainActivity::class.java,
).apply {
    data = Uri.parse(link)
    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
