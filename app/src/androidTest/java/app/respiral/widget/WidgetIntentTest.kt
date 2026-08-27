package app.respiral.widget

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WidgetIntentTest {
    @Test
    fun quiet_return_widget_deep_link_opens_reflection() {
        assertThat(parseAppRoute(Uri.parse("respiral://reflection"))).isEqualTo("reflection")
    }

    @Test
    fun two_ways_in_capture_action_opens_editor() {
        assertThat(parseAppRoute(Uri.parse("respiral://capture"))).isEqualTo("editor")
    }
}
