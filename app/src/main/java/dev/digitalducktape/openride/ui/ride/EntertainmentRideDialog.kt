package dev.digitalducktape.openride.ui.ride

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.digitalducktape.openride.entertainment.EntertainmentOverlayService

/** External apps are optional; do not silently send users to an app store. */
enum class EntertainmentTarget(val displayName: String, val packageName: String) {
    Plex("Plex", "com.plexapp.android"),
    Kindle("Kindle", "com.amazon.kindle"),
    YouTubeTv("YouTube TV (experimental)", "com.google.android.apps.youtube.unplugged"),
}

/**
 * This must run from the OpenRide screen after a ride has already started.
 * The app switch is user initiated, not a background activity launch.
 */
object EntertainmentRideLauncher {
    fun launch(context: Context, target: EntertainmentTarget, showOverlay: Boolean): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(target.packageName)
        if (intent == null) {
            Toast.makeText(context, target.displayName + " isn't installed on this tablet yet.", Toast.LENGTH_LONG).show()
            return false
        }
        if (showOverlay && !Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "Allow Display over other apps, then tap Entertainment again.", Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
            )
            return false
        }
        EntertainmentOverlayService.start(context, visible = showOverlay)
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            Toast.makeText(context, "Couldn't launch " + target.displayName + ". " + error.message, Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }
}

@Composable
fun EntertainmentRideDialog(
    onDismiss: () -> Unit,
    onLaunch: (EntertainmentTarget, Boolean) -> Unit,
    onRestoreOverlay: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Entertainment ride") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your current ride keeps recording. Choose what to open on the bike's screen.")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onLaunch(EntertainmentTarget.Plex, true) }) {
                        Text("Watch Plex")
                    }
                    OutlinedButton(onClick = { onLaunch(EntertainmentTarget.Kindle, false) }) {
                        Text("Read Kindle")
                    }
                }
                OutlinedButton(onClick = { onLaunch(EntertainmentTarget.YouTubeTv, true) }) {
                    Text("YouTube TV (test)")
                }
                Text("Plex and YouTube TV show a movable metrics panel. Kindle hides it entirely. Tap the panel's gear to choose individual metrics.")
                Text("Return to OpenRide from Android's app switcher or the ride notification to end and save your workout.")
            }
        },
        confirmButton = {
            TextButton(onClick = onRestoreOverlay) { Text("Show panel") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
