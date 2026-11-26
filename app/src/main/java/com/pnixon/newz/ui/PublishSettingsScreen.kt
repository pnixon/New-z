@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.pnixon.newz.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
private fun Entry(label: String, value: String, onChange: (String) -> Unit, hint: String? = null, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun Block(title: String, note: String? = null) {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
}

private fun copy(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }
}

/** Publishing the articles you share as a signed Ansybl feed in your own storage bucket. */
@Composable
fun PublishSettingsPage(vm: MainViewModel) {
    val context = LocalContext.current
    val saved by vm.publish.collectAsStateWithLifecycle()
    val shared by vm.shared.collectAsStateWithLifecycle()
    val status by vm.publishStatus.collectAsStateWithLifecycle()
    val publicKey by vm.publicKey.collectAsStateWithLifecycle()
    val hasSecret by vm.hasPublishSecret.collectAsStateWithLifecycle()
    val publishing by vm.publishing.collectAsStateWithLifecycle()

    var draft by remember(saved) { mutableStateOf(saved) }
    var maxItems by remember(saved) { mutableStateOf(saved.maxItems.toString()) }
    var secretInput by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.ensureIdentity() }

    val edited = draft.copy(maxItems = (maxItems.toIntOrNull() ?: draft.maxItems).coerceIn(1, 1000))
    val dirty = edited != saved

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Articles you mark with \"Share to my published feed\" (on an article's ⓘ sheet) are written to one signed feed file in the Ansybl format and uploaded to your own storage bucket. " +
                "Only what you share is published: the headline, a summary, the source and a link, never the article text.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !publishing && !dirty && saved.ready && hasSecret, onClick = { vm.publishNow() }) {
                Text(if (publishing) "Publishing…" else "Publish now")
            }
            if (saved.feedUrl.isNotBlank()) {
                OutlinedButton(onClick = { copy(context, "Feed address", saved.feedUrl); vm.say("Feed address copied") }) { Text("Copy feed address") }
            }
        }
        if (dirty) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.savePublish(edited); vm.say("Publishing settings saved") }) { Text("Save changes") }
                OutlinedButton(onClick = { draft = saved.copy(); maxItems = saved.maxItems.toString() }) { Text("Discard") }
            }
        }

        Block("The feed")
        Entry("Title", draft.title, { draft = draft.copy(title = it) })
        Entry("Description", draft.description, { draft = draft.copy(description = it) })
        Entry("Your name as author", draft.authorName, { draft = draft.copy(authorName = it) }, hint = "Blank: the title is used")
        Entry("Public address of the feed file", draft.feedUrl, { draft = draft.copy(feedUrl = it.trim()) }, hint = "What subscribers paste into their reader, ending in the file name", keyboard = KeyboardType.Uri)
        Entry("Home page", draft.homePageUrl, { draft = draft.copy(homePageUrl = it.trim()) }, hint = "Blank: the feed address is used", keyboard = KeyboardType.Uri)
        Entry("Newest articles to keep in the feed", maxItems, { maxItems = it.filter(Char::isDigit) }, hint = "Readers reject files over 2 MB; the feed is trimmed to fit", keyboard = KeyboardType.Number)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Publish when the shared list changes", style = MaterialTheme.typography.bodyLarge)
                Text("Off: nothing is uploaded until you press Publish now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = draft.autoPublish, onCheckedChange = { draft = draft.copy(autoPublish = it) })
        }

        Block(
            "Storage",
            "Any S3-compatible bucket. For Cloudflare R2: create a bucket, give it public read access (its r2.dev address or your own domain), " +
                "add a CORS rule allowing GET from any origin so web readers can fetch it, and create an R2 API token with object read and write, " +
                "which gives you the access key ID and secret. The endpoint is https://YOUR_ACCOUNT_ID.r2.cloudflarestorage.com and the region is auto.",
        )
        Entry("Endpoint", draft.endpoint, { draft = draft.copy(endpoint = it.trim()) }, keyboard = KeyboardType.Uri)
        Entry("Bucket", draft.bucket, { draft = draft.copy(bucket = it.trim()) })
        Entry("File name in the bucket", draft.objectKey, { draft = draft.copy(objectKey = it.trim()) })
        Entry("Region", draft.region, { draft = draft.copy(region = it.trim()) })
        Entry("Access key ID", draft.accessKeyId, { draft = draft.copy(accessKeyId = it.trim()) })
        Text(
            if (hasSecret) "A secret access key is saved on this phone, encrypted. It is never exported or backed up." else "No secret access key saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = secretInput,
            onValueChange = { secretInput = it },
            label = { Text(if (hasSecret) "Replace secret access key" else "Secret access key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = secretInput.isNotBlank(), onClick = { vm.setPublishSecret(secretInput); secretInput = "" }) { Text("Save secret") }
            if (hasSecret) OutlinedButton(onClick = { vm.setPublishSecret(null) }) { Text("Remove secret") }
        }

        Block(
            "Your Ansybl identity",
            "An Ed25519 key made on this phone. Every item and the feed itself are signed with it, and the public half is in the feed so readers can check the signatures.",
        )
        Text(publicKey ?: "Creating…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        remember(publicKey) { vm.identityFingerprint() }?.let {
            Text("Fingerprint: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { publicKey?.let { copy(context, "Public key", it); vm.say("Public key copied") } }) { Text("Copy public key") }
            OutlinedButton(onClick = {
                vm.privateKeyForBackup()?.let { copy(context, "Private key", it); vm.say("Private key copied. Anyone holding it can publish as you; store it somewhere safe.") }
            }) { Text("Copy private key for backup") }
            OutlinedButton(onClick = { importing = true }) { Text("Import a private key") }
            OutlinedButton(onClick = { confirmNew = true }) { Text("New identity") }
        }
        Text(
            "The private key is stored encrypted on this phone only and is lost if the app's data is cleared, so keep a backup if the identity matters to you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Block("Shared articles", "${shared.size} shared. The newest ${edited.maxItems} are published.")
        if (shared.isEmpty()) {
            Text("Nothing shared yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        shared.sortedByDescending { it.sharedAt }.take(100).forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text("${item.sourceName} · shared ${ago(item.sharedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.unshare(item.url) }) { Text("Remove") }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (importing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Import a private key") },
            text = {
                Column {
                    Text("Replaces this phone's identity. Use the ed25519:BASE64 form.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { text = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { vm.importIdentity(text); importing = false }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } },
        )
    }
    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Create a new identity?") },
            text = { Text("The current key is discarded unless you have backed it up. Readers will see your feed as coming from a different author.") },
            confirmButton = { TextButton(onClick = { vm.newIdentity(); confirmNew = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancel") } },
        )
    }
}
