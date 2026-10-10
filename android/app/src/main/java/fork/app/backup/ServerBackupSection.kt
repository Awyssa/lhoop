// Fork-owned. The Strap tab's part for the server backup: its state, Back up now, and where the
// owner gives the server's address and token.
package fork.app.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId

/** Called inside the Strap tab's column. */
@Composable
internal fun ServerBackupSection() {
    val context = LocalContext.current
    val status by remember { ServerBackup.status(context) }.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()

    Text("Server backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("Backup", modifier = Modifier.weight(0.5f).padding(end = 12.dp))
        Text(BackupWords.line(status, now, ZoneId.systemDefault()), modifier = Modifier.weight(0.5f))
    }
    status.address?.let { server ->
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Server", modifier = Modifier.weight(0.5f).padding(end = 12.dp))
            Text(server.substringAfter("://"), modifier = Modifier.weight(0.5f))
        }
    }
    BackupWords.warning(status, now)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    if (editing) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; problem = null },
            label = { Text("Server address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        // The token is shown once by the server and kept only here. It is never drawn in the clear.
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; problem = null },
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        problem?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                problem = ServerBackup.addressProblem(address)
                    ?: "Paste the token the server printed.".takeIf { token.isBlank() }
                if (problem == null) {
                    ServerBackup.setUp(context, address, token)
                    token = ""
                    editing = false
                }
            }) { Text("Save") }
            TextButton(onClick = { token = ""; problem = null; editing = false }) { Text("Cancel") }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.setUp) {
                Button(onClick = { ServerBackup.runSoon(context) }, enabled = !status.running) { Text("Back up now") }
            }
            Button(onClick = { address = status.address.orEmpty(); editing = true }) {
                Text(if (status.setUp) "Change server" else "Set up")
            }
            if (status.setUp) TextButton(onClick = { ServerBackup.forget(context) }) { Text("Forget") }
        }
    }
}
