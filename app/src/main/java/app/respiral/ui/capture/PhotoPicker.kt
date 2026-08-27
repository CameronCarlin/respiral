package app.respiral.ui.capture

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.respiral.data.vault.PendingMedia
import java.io.File
import java.util.UUID

@Composable
fun PhotoPicker(
    onPhotoSelected: (PendingMedia) -> Unit,
    onSelectionFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var cameraCapture by remember { mutableStateOf<CameraCapture?>(null) }
    val libraryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            onSelectionFailure()
        } else {
            onPhotoSelected(PendingMedia(uri, context.contentResolver.getType(uri) ?: "image/jpeg"))
        }
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val capture = cameraCapture
        cameraCapture = null
        if (saved && capture != null) {
            onPhotoSelected(PendingMedia(capture.uri, "image/jpeg"))
        } else {
            capture?.file?.delete()
            onSelectionFailure()
        }
    }

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                libraryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        ) { Text("Choose photo") }
        OutlinedButton(
            onClick = {
                val capture = createCameraCapture(context)
                cameraCapture = capture
                cameraPicker.launch(capture.uri)
            },
        ) { Text("Take photo") }
    }
}

private data class CameraCapture(val file: File, val uri: Uri)

private fun createCameraCapture(context: Context): CameraCapture {
    val directory = File(context.cacheDir, "capture").apply { mkdirs() }
    val file = File(directory, "capture-${UUID.randomUUID()}.jpg")
    return CameraCapture(
        file = file,
        uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
    )
}
