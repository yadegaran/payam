package ir.payam.messenger.ui

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.payam.messenger.BuildConfig
import ir.payam.messenger.core.L
import ir.payam.messenger.core.Settings
import ir.payam.messenger.core.Updater
import kotlinx.coroutines.launch
import java.io.File

/** "New version available" card: download (with progress), verify, install. */
@Composable
fun UpdateCard(act: Activity, inSettings: Boolean = false) {
    val rel by Updater.available.collectAsState()
    val progress by Updater.progress.collectAsState()
    val err by Updater.error.collectAsState()
    Settings.rev.intValue
    val r = rel ?: return
    if (!inSettings && Settings.skipUpdate == r.version) return
    val scope = rememberCoroutineScope()
    var file by remember(r.version) { mutableStateOf<File?>(null) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Accent.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(L("نسخه جدید ${r.version} آماده است", "Version ${r.version} is available"), fontWeight = FontWeight.Bold)
            Text(L("نسخه فعلی: ", "Installed: ") + BuildConfig.VERSION_NAME, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = progress == null, onClick = {
                    val ready = file?.takeIf { it.exists() }
                    if (ready != null) Updater.install(act, ready)
                    else scope.launch { Updater.download(act, r)?.let { file = it; Updater.install(act, it) } }
                }) {
                    Text(
                        when {
                            progress != null -> L("در حال دانلود… ${((progress ?: 0f) * 100).toInt()}٪", "Downloading… ${((progress ?: 0f) * 100).toInt()}%")
                            file != null -> L("نصب", "Install")
                            else -> L("دانلود و نصب", "Download & install")
                        }
                    )
                }
                if (!inSettings && progress == null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton({ Settings.skipUpdate = r.version }) { Text(L("بعداً", "Later")) }
                }
            }
        }
    }
}
