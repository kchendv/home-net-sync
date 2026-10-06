package com.example.homenetsync

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.Manifest
import androidx.core.app.NotificationManagerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private var update: (() -> Unit)? = null
    private var afterNotificationPermission: (() -> Unit)? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterNotificationPermission?.invoke()
        afterNotificationPermission = null
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.sources = (prefs.sources + uri.toString()).distinct()
            update?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        WorkManager.getInstance(this).cancelUniqueWork("home-sync")
        setContent {
            var redraw by remember { mutableIntStateOf(0) }
            update = { redraw++ }
            val sources = remember(redraw) { prefs.sources }
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF386A5A), background = Color(0xFFF5F7F4))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SyncScreen(
                        prefs, sources,
                        onPick = { folderPicker.launch(null) },
                        onUpdate = { redraw++ },
                        onSync = { withNotifications { enqueue() } },
                        onCancel = { WorkManager.getInstance(this).cancelUniqueWork("manual-sync") }
                    )
                }
            }
        }
    }

    private fun enqueue() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        ).build()
        WorkManager.getInstance(this).enqueueUniqueWork("manual-sync", ExistingWorkPolicy.REPLACE, request)
    }

    private fun withNotifications(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            afterNotificationPermission = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
}

@Composable
private fun SyncScreen(p: Prefs, sources: List<String>, onPick: () -> Unit, onUpdate: () -> Unit, onSync: () -> Unit, onCancel: () -> Unit) {
    var host by remember { mutableStateOf(p.host) }
    var share by remember { mutableStateOf(p.share) }
    var user by remember { mutableStateOf(p.user) }
    var password by remember { mutableStateOf(p.password) }
    var domain by remember { mutableStateOf(p.domain) }
    var destination by remember { mutableStateOf(p.destination) }
    var afterDate by remember { mutableStateOf(p.afterDate) }
    var beforeDate by remember { mutableStateOf(p.beforeDate) }
    var status by remember { mutableStateOf(p.status) }
    var checking by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf("") }
    var driveSaved by remember { mutableStateOf(p.driveConnected) }
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val manualFlow = remember { WorkManager.getInstance(p.context).getWorkInfosForUniqueWorkFlow("manual-sync") }
    val manual by manualFlow.collectAsState(initial = emptyList())
    val active = manual.let { infos ->
        infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
    }
    val transfer = active?.toTransfer()
    LaunchedEffect(manual.map { it.state }) {
        if (active == null && manual.lastOrNull()?.state == WorkInfo.State.CANCELLED) {
            status = "Sync cancelled"
            p.status = status
        } else {
            status = p.status
        }
    }
    fun fieldsMatchSaved() = driveSaved && host == p.host && share == p.share && user == p.user &&
        password == p.password && domain == p.domain && destination == p.destination
    val connected = !checking && connectError.isEmpty() && fieldsMatchSaved()
    val invalidDateRange = afterDate != null && beforeDate != null && afterDate!! > beforeDate!!
    fun connect(andThen: (() -> Unit)?) {
        if (checking) return
        if (andThen != null && fieldsMatchSaved()) {
            andThen()
            return
        }
        checking = true
        connectError = ""
        val form = DriveForm(host, share, user, password, domain, destination)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Smb.test(form.host, form.share, form.user, form.password, form.domain, form.destination) }
            }
            checking = false
            result.onSuccess {
                if (p.saveDrive(form.host, form.share, form.user, form.password, form.domain, form.destination)) {
                    host = p.host
                    share = p.share
                    user = p.user
                    password = p.password
                    domain = p.domain
                    destination = p.destination
                    driveSaved = true
                    connectError = ""
                    andThen?.invoke()
                } else {
                    driveSaved = false
                    connectError = "Could not save the drive configuration"
                }
            }.onFailure { error ->
                val failedSaved = driveSaved && form.host.trim() == p.host && form.share.trim() == p.share &&
                    form.user == p.user && form.password == p.password && form.domain == p.domain && form.destination == p.destination
                if (failedSaved) {
                    p.markDriveDisconnected()
                    driveSaved = false
                }
                connectError = "Could not connect: ${error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName}"
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp)) {
        item {
            Text("Home Net Sync", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF21352E))
            Text("Copy newer phone files to your home drive", color = Color(0xFF58665F))
        }
        item { SectionCard("Phone folders") {
            Text("Choose one or more folders to scan recursively.", color = Color(0xFF58665F))
            sources.forEach { uri ->
                val label = DocumentFile.fromTreeUri(p.context, Uri.parse(uri))?.name ?: "Selected folder"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { p.sources = p.sources - uri; onUpdate() }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Add phone folder") }
        } }
        item { SectionCard("Home network drive (SMB)") {
            fun edited(update: (String) -> Unit): (String) -> Unit = { value ->
                connectError = ""
                update(value)
            }
            Field("Server address", host, edited { host = it }, "192.168.1.20")
            Field("Share name", share, edited { share = it }, "Shared")
            Field("Username", user, edited { user = it }, "Optional")
            Field("Password", password, edited { password = it }, "Optional", secret = true)
            Field("Domain (optional)", domain, edited { domain = it }, "WORKGROUP")
            Field("Destination folder", destination, edited { destination = it }, "Archive/New Folder/Destination")
            Text("Folders are created inside the share. Enter a path relative to the share root.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF58665F))
            Button(onClick = { connect(null) }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                Text(if (checking) "Connecting…" else "Connect")
            }
            DriveStatus(checking, connected, connectError, host, share)
        } }
        item { SectionCard("Date range") {
            Text("Optionally copy files modified on or after the AFTER date and on or before the BEFORE date.", color = Color(0xFF58665F))
            DateRangeOption(p.context, "AFTER", afterDate, dateFormat, { afterDate = it }, { afterDate = null }, onUpdate)
            DateRangeOption(p.context, "BEFORE", beforeDate, dateFormat, { beforeDate = it }, { beforeDate = null }, onUpdate)
            if (invalidDateRange) Text("AFTER must be on or before BEFORE.", color = MaterialTheme.colorScheme.error)
        } }
        item { SectionCard("Sync") {
            if (transfer != null) {
                val fraction = transfer.fraction
                Text(transfer.label, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF21352E))
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                if (transfer.detail.isNotBlank()) {
                    Text(transfer.detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF58665F))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    connect {
                        p.afterDate = afterDate
                        p.beforeDate = beforeDate
                        status = "Sync queued"
                        p.status = status
                        onSync()
                    }
                }, enabled = !checking && transfer == null && !invalidDateRange, modifier = Modifier.weight(1f)) { Text("Sync now") }
                if (transfer != null) {
                    OutlinedButton(onClick = {
                        status = "Cancelling sync…"
                        p.status = status
                        onCancel()
                    }, modifier = Modifier.weight(1f)) { Text("Cancel sync") }
                }
            }
            Text(status.ifBlank { "Ready" }, style = MaterialTheme.typography.bodySmall, color = Color(0xFF386A5A))
        } }
    }
}

@Composable
private fun DateRangeOption(
    context: Context,
    label: String,
    value: Long?,
    dateFormat: SimpleDateFormat,
    onSelect: (Long) -> Unit,
    onClear: () -> Unit,
    onUpdate: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val calendar = Calendar.getInstance().apply { timeInMillis = value ?: System.currentTimeMillis() }
            DatePickerDialog(context, { _, year, month, day ->
                onSelect(Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis)
                onUpdate()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }, modifier = Modifier.weight(1f)) {
            Text(if (value == null) "$label: Any date" else "$label: ${dateFormat.format(Date(value))}")
        }
        if (value != null) TextButton(onClick = onClear) { Text("Clear") }
    }
}

@Composable
private fun DriveStatus(checking: Boolean, connected: Boolean, error: String, host: String, share: String) {
    val color = when {
        checking -> Color(0xFF8A948F)
        connected -> Color(0xFF1B7F4E)
        error.isNotBlank() -> MaterialTheme.colorScheme.error
        else -> Color(0xFF8A948F)
    }
    val label = when {
        checking -> "Looking for the network drive…"
        connected -> "Connected to ${host.trim()} / ${share.trim()}"
        error.isNotBlank() -> error
        else -> "Not connected"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
        } else {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

private fun WorkInfo.toTransfer(): TransferUi {
    if (state == WorkInfo.State.ENQUEUED) return TransferUi("Waiting for an unmetered Wi-Fi connection", null, "")
    val phase = progress.getString(SyncProgress.PHASE)
    val done = progress.getLong(SyncProgress.DONE, 0L)
    val total = progress.getLong(SyncProgress.TOTAL, 0L)
    val name = progress.getString(SyncProgress.NAME).orEmpty()
    return when (phase) {
        SyncProgress.SCANNING -> TransferUi("Looking for files to copy", null, "")
        SyncProgress.COPYING -> TransferUi(
            if (name.isBlank()) "Copying files" else "Copying $name",
            if (total > 0L) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null,
            if (total > 0L) "${formatBytes(done)} of ${formatBytes(total)}" else ""
        )
        else -> TransferUi("Connecting to the drive", null, "")
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = "B"
    for (next in units) {
        value /= 1024.0
        unit = next
        if (value < 1024.0 || next == units.last()) break
    }
    return String.format(Locale.US, if (value >= 10) "%.0f %s" else "%.1f %s", value, unit)
}

private data class TransferUi(val label: String, val fraction: Float?, val detail: String)

private data class DriveForm(val host: String, val share: String, val user: String, val password: String, val domain: String, val destination: String)

@Composable private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color(0xFF21352E)); content()
        }
    }
}

@Composable private fun Field(label: String, value: String, change: (String) -> Unit, placeholder: String, secret: Boolean = false) {
    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = change,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (secret && !passwordVisible) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text,
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
            autoCorrectEnabled = false
        ),
        visualTransformation = if (secret && !passwordVisible)
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = if (secret) {
            { TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "Hide" else "Show") } }
        } else null
    )
}

class Prefs(val context: Context) {
    private val sp = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    var host: String get() = sp.getString("host", "")!!; set(v) = sp.edit().putString("host", v).apply()
    var share: String get() = sp.getString("share", "")!!; set(v) = sp.edit().putString("share", v).apply()
    var user: String get() = sp.getString("user", "")!!; set(v) = sp.edit().putString("user", v).apply()
    var password: String get() = sp.getString("password", "")!!; set(v) = sp.edit().putString("password", v).apply()
    var domain: String get() = sp.getString("domain", "")!!; set(v) = sp.edit().putString("domain", v).apply()
    var destination: String get() = sp.getString("destination", "")!!; set(v) = sp.edit().putString("destination", v).apply()
    var afterDate: Long?
        get() = if (sp.contains("afterDate")) sp.getLong("afterDate", 0L) else null
        set(v) { sp.edit().apply { if (v == null) remove("afterDate") else putLong("afterDate", v) }.apply() }
    var beforeDate: Long?
        get() = if (sp.contains("beforeDate")) sp.getLong("beforeDate", 0L) else null
        set(v) { sp.edit().apply { if (v == null) remove("beforeDate") else putLong("beforeDate", v) }.apply() }
    var status: String get() = sp.getString("status", "")!!; set(v) = sp.edit().putString("status", v).apply()
    var driveConnected: Boolean get() = sp.getBoolean("driveConnected", false); set(v) = sp.edit().putBoolean("driveConnected", v).apply()
    var sources: List<String>
        get() = sp.getString("sources", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) { sp.edit().putString("sources", v.joinToString("\n")).apply() }
    fun saveDrive(h: String, s: String, u: String, pw: String, d: String, dest: String): Boolean = sp.edit()
        .putString("host", h.trim())
        .putString("share", s.trim())
        .putString("user", u)
        .putString("password", pw)
        .putString("domain", d)
        .putString("destination", dest)
        .putBoolean("driveConnected", true)
        .commit()

    fun markDriveDisconnected() {
        sp.edit().putBoolean("driveConnected", false).apply()
    }
}

