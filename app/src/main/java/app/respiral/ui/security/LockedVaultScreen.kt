package app.respiral.ui.security

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun LockedVaultScreen(
    onAuthenticate: () -> Unit,
    isAuthenticating: Boolean = false,
    message: String? = null,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text("Respiral", style = MaterialTheme.typography.displaySmall)
            Text("Your private vault is resting.", style = MaterialTheme.typography.headlineSmall)
            Text("Unlock with the same fingerprint or device passcode you already use.")
            Button(
                modifier = Modifier.fillMaxWidth().testTag("authenticate-vault"),
                enabled = !isAuthenticating,
                onClick = onAuthenticate,
            ) {
                Text(if (isAuthenticating) "Waiting…" else "Unlock Respiral")
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
