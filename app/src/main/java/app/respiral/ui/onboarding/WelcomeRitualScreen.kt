package app.respiral.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
data class WelcomePrompt(val prompt: String)

@Composable
fun WelcomeRitualScreen(
    onPromptSelected: (WelcomePrompt) -> Unit,
    onSkip: () -> Unit,
    onFreeformEntry: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(text = "Welcome to Respiral", style = MaterialTheme.typography.headlineMedium)
            Text("Choose a gentle place to begin, or write whatever is on your mind.")
            WelcomePromptButton("Something I handled well") {
                onPromptSelected(WelcomePrompt("Something I handled well"))
            }
            WelcomePromptButton("What people appreciate about me") {
                onPromptSelected(WelcomePrompt("What people appreciate about me"))
            }
            WelcomePromptButton("A reminder for a difficult day") {
                onPromptSelected(WelcomePrompt("A reminder for a difficult day"))
            }
            TextButton(onClick = onFreeformEntry) { Text("Add something good") }
            TextButton(onClick = onSkip) { Text("Skip") }
        }
    }
}

@Composable
private fun WelcomePromptButton(text: String, onClick: () -> Unit) {
    Button(modifier = Modifier.fillMaxWidth(), onClick = onClick) { Text(text) }
}
