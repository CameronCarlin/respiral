package app.respiral.ui.arrival

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The intentionally small first choice shown after onboarding. */
@Composable
fun ArrivalScreen(
    onRemindMe: () -> Unit,
    onAddEntry: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(text = "Respiral", style = MaterialTheme.typography.displaySmall)
            Text(
                text = "A quiet place for the good things you know about yourself.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(modifier = Modifier.fillMaxWidth(), onClick = onRemindMe) {
                Text("Remind me who I am")
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onAddEntry) {
                Text("Add something good")
            }
        }
    }
}
