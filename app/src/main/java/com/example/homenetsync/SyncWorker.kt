package com.example.homenetsync

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar

internal object SyncProgress {
    const val NOTIFICATION_CHANNEL = "sync-progress"
    const val NOTIFICATION_ID = 1001
    const val PHASE = "phase"
    const val DONE = "done"
    const val TOTAL = "total"
    const val NAME = "name"
    const val CONNECTING = "connecting"
    const val SCANNING = "scanning"
    const val COPYING = "copying"
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var lastReport = 0L

    override suspend fun doWork(): Result {
        createNotificationChannel()
        val prefs = Prefs(applicationContext)
        if (prefs.host.isBlank() || prefs.share.isBlank()) return fail(prefs, "Enter the SMB server and share name")
        if (prefs.sources.isEmpty()) return fail(prefs, "Choose at least one phone folder")
        report(SyncProgress.CONNECTING, 0, 0, "", force = true)
        val normalizedRoot = Smb.cleanPath(prefs.destination)
        var copied = 0
        var skipped = 0
        try {
            Smb.useShare(prefs.host, prefs.share, prefs.user, prefs.password, prefs.domain, timeoutSeconds = 120) { remote ->
                Smb.ensureDirectories(remote, normalizedRoot)
                report(SyncProgress.SCANNING, 0, 0, "", force = true)
                val pending = mutableListOf<Pending>()
                val beforeExclusive = prefs.beforeDate?.let { date ->
                    Calendar.getInstance().apply {
                        timeInMillis = date
                        add(Calendar.DAY_OF_MONTH, 1)
                    }.timeInMillis
                }
                for (treeUri in prefs.sources) {
                    val source = DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUri)) ?: continue
                    collect(source, source.name ?: "Phone", "", remote, normalizedRoot, prefs.afterDate, beforeExclusive, pending) { skipped++ }
                }
                val useBytes = pending.any { it.size > 0L }
                val total = if (useBytes) pending.sumOf { it.size } else pending.size.toLong()
                var done = 0L
                report(SyncProgress.COPYING, 0, total, "", force = true)
                for (item in pending) {
                    report(SyncProgress.COPYING, done, total, item.name, force = true)
                    val input = applicationContext.contentResolver.openInputStream(item.uri)
                    if (input == null) {
                        skipped++
                        done = advance(done, item.size, 0, useBytes)
                        continue
                    }
                    var written = 0L
                    input.use { stream ->
                        remote.openOutput(item.remote).use { output ->
                            written = copy(stream, output) { soFar ->
                                val shown = if (useBytes) done + soFar else done
                                report(SyncProgress.COPYING, shown, total, item.name)
                            }
                        }
                    }
                    copied++
                    done = advance(done, item.size, written, useBytes)
                    report(SyncProgress.COPYING, done, total, item.name, force = true)
                }
            }
            prefs.status = "Finished: $copied copied, $skipped skipped"
            NotificationManagerCompat.from(applicationContext).cancel(SyncProgress.NOTIFICATION_ID)
            return Result.success()
        } catch (e: CancellationException) {
            NotificationManagerCompat.from(applicationContext).cancel(SyncProgress.NOTIFICATION_ID)
            throw e
        } catch (e: Exception) {
            NotificationManagerCompat.from(applicationContext).cancel(SyncProgress.NOTIFICATION_ID)
            return fail(prefs, "Sync failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun collect(
        node: DocumentFile,
        sourceName: String,
        relative: String,
        remote: SmbShare,
        destination: String,
        afterDate: Long?,
        beforeExclusive: Long?,
        pending: MutableList<Pending>,
        onSkip: () -> Unit
    ) {
        if (node.isDirectory) {
            val dirRelative = listOf(sourceName, relative).filter { it.isNotBlank() }.joinToString("/")
            val dir = Smb.join(destination, dirRelative)
            Smb.ensureDirectories(remote, dir)
            node.listFiles().forEach { child ->
                val childRelative = listOf(relative, child.name ?: "unnamed").filter { it.isNotBlank() }.joinToString("/")
                collect(child, sourceName, childRelative, remote, destination, afterDate, beforeExclusive, pending, onSkip)
            }
        } else if (node.isFile) {
            val modified = node.lastModified()
            if ((afterDate != null && modified < afterDate) || (beforeExclusive != null && modified >= beforeExclusive)) {
                onSkip()
                return
            }
            val remotePath = Smb.join(destination, listOf(sourceName, relative).filter { it.isNotBlank() }.joinToString("/"))
            val parent = remotePath.substringBeforeLast('/', "")
            Smb.ensureDirectories(remote, parent)
            // Skip existing files. This keeps repeated scheduled runs idempotent and avoids
            // replacing a newer copy already on the drive.
            if (remote.fileExists(remotePath)) {
                onSkip()
                return
            }
            pending += Pending(node.uri, remotePath, node.name ?: "file", node.length().coerceAtLeast(0L))
        }
    }

    private suspend fun copy(input: InputStream, output: OutputStream, onProgress: suspend (Long) -> Unit): Long {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            written += read
            onProgress(written)
        }
        return written
    }

    private fun advance(done: Long, planned: Long, written: Long, useBytes: Boolean): Long {
        if (!useBytes) return done + 1
        return done + planned.coerceAtLeast(written)
    }

    private suspend fun report(phase: String, done: Long, total: Long, name: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReport < 300) return
        lastReport = now
        setProgress(workDataOf(
            SyncProgress.PHASE to phase,
            SyncProgress.DONE to done,
            SyncProgress.TOTAL to total,
            SyncProgress.NAME to name
        ))
        postNotification(phase, done, total, name)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SyncProgress.NOTIFICATION_CHANNEL,
                "Sync progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Progress updates while files are syncing" }
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun postNotification(phase: String, done: Long, total: Long, name: String) {
        val title: String
        val text: String
        val progress: Int
        val indeterminate: Boolean
        when (phase) {
            SyncProgress.CONNECTING -> {
                title = "Connecting to home drive"
                text = "Preparing the sync"
                progress = 0
                indeterminate = true
            }
            SyncProgress.SCANNING -> {
                title = "Scanning phone folders"
                text = "Looking for files to copy"
                progress = 0
                indeterminate = true
            }
            else -> {
                title = "Copying files"
                text = if (name.isBlank()) "Preparing files" else "Copying $name"
                progress = if (total > 0L) ((done * 100L / total).coerceIn(0L, 100L)).toInt() else 0
                indeterminate = total <= 0L
            }
        }
        val notification = NotificationCompat.Builder(applicationContext, SyncProgress.NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(SyncProgress.NOTIFICATION_ID, notification) }
    }

    private fun fail(prefs: Prefs, message: String): Result {
        prefs.status = message
        return Result.retry()
    }

    private class Pending(val uri: Uri, val remote: String, val name: String, val size: Long)
}
