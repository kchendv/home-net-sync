package com.example.homenetsync

import android.content.Context
import android.content.pm.ServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        setForeground(foregroundInfo(SyncProgress.CONNECTING, 0, 0, ""))
        val normalizedRoot = Smb.cleanPath(prefs.destination)
        val totals = Totals()
        try {
            val beforeExclusive = prefs.beforeDate?.let { date ->
                Calendar.getInstance().apply {
                    timeInMillis = date
                    add(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis
            }
            val roots = prefs.sources.mapNotNull { treeUri ->
                DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUri))
            }
            report(SyncProgress.SCANNING, 0, 0, "", force = true)
            for (source in roots) measure(source, prefs.afterDate, beforeExclusive, totals)
            report(SyncProgress.SCANNING, totals.scanned, 0, "", force = true)

            report(SyncProgress.CONNECTING, 0, 0, "", force = true)
            Smb.useShare(prefs.host, prefs.share, prefs.user, prefs.password, prefs.domain, timeoutSeconds = 120) { remote ->
                Smb.ensureDirectories(remote, normalizedRoot)
                report(SyncProgress.COPYING, 0, totals.progressTotal, "", force = true)
                for (source in roots) {
                    copyTree(source, source.name ?: "Phone", "", remote, normalizedRoot,
                        prefs.afterDate, beforeExclusive, totals)
                }
            }
            report(SyncProgress.COPYING, totals.progressTotal, totals.progressTotal, "", force = true)
            prefs.status = "Finished: ${totals.copied} copied, ${totals.skipped} skipped"
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

    private suspend fun measure(
        node: DocumentFile,
        afterDate: Long?,
        beforeExclusive: Long?,
        totals: Totals
    ) {
        currentCoroutineContext().ensureActive()
        if (node.isDirectory) {
            for (child in node.listFiles()) measure(child, afterDate, beforeExclusive, totals)
        } else if (node.isFile) {
            val modified = node.lastModified()
            totals.scanned++
            report(SyncProgress.SCANNING, totals.scanned, 0, "")
            if (isInRange(modified, afterDate, beforeExclusive)) {
                totals.eligibleFiles++
                totals.totalBytes += node.length().coerceAtLeast(0L)
            }
        }
    }

    private suspend fun copyTree(
        node: DocumentFile,
        sourceName: String,
        relative: String,
        remote: SmbShare,
        destination: String,
        afterDate: Long?,
        beforeExclusive: Long?,
        totals: Totals
    ) {
        currentCoroutineContext().ensureActive()
        if (node.isDirectory) {
            val dirRelative = listOf(sourceName, relative).filter { it.isNotBlank() }.joinToString("/")
            Smb.ensureDirectories(remote, Smb.join(destination, dirRelative))
            for (child in node.listFiles()) {
                val childRelative = listOf(relative, child.name ?: "unnamed").filter { it.isNotBlank() }.joinToString("/")
                copyTree(child, sourceName, childRelative, remote, destination, afterDate, beforeExclusive, totals)
            }
        } else if (node.isFile) {
            val size = node.length().coerceAtLeast(0L)
            if (!isInRange(node.lastModified(), afterDate, beforeExclusive)) {
                totals.skipped++
                return
            }
            val name = node.name ?: "file"
            val remotePath = Smb.join(destination, listOf(sourceName, relative).filter { it.isNotBlank() }.joinToString("/"))
            val useBytes = totals.totalBytes > 0L
            val planned = if (useBytes) size else 1L
            // Keep repeated syncs idempotent and avoid replacing an existing remote file.
            if (remote.fileExists(remotePath)) {
                totals.skipped++
                totals.done += planned
                report(SyncProgress.COPYING, totals.done, totals.progressTotal, name, force = true)
                return
            }
            val input = applicationContext.contentResolver.openInputStream(node.uri)
            if (input == null) {
                totals.skipped++
                totals.done += planned
                report(SyncProgress.COPYING, totals.done, totals.progressTotal, name, force = true)
                return
            }
            var written = 0L
            input.use { stream ->
                remote.openOutput(remotePath).use { output ->
                    written = copy(stream, output) { soFar ->
                        report(SyncProgress.COPYING, totals.done + (if (useBytes) soFar else 0L),
                            totals.progressTotal, name)
                    }
                }
            }
            totals.copied++
            totals.done += if (useBytes) size.coerceAtLeast(written) else 1L
            report(SyncProgress.COPYING, totals.done, totals.progressTotal, name, force = true)
        }
    }

    private fun isInRange(modified: Long, afterDate: Long?, beforeExclusive: Long?) =
        (afterDate == null || modified >= afterDate) && (beforeExclusive == null || modified < beforeExclusive)

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
        runCatching { NotificationManagerCompat.from(applicationContext).notify(SyncProgress.NOTIFICATION_ID, buildNotification(phase, done, total, name)) }
    }

    private fun foregroundInfo(phase: String, done: Long, total: Long, name: String): ForegroundInfo {
        val notification = buildNotification(phase, done, total, name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(SyncProgress.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(SyncProgress.NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(phase: String, done: Long, total: Long, name: String) = run {
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
                text = if (done > 0L) "$done files scanned" else "Looking for files to copy"
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
        NotificationCompat.Builder(applicationContext, SyncProgress.NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .build()
    }

    private fun fail(prefs: Prefs, message: String): Result {
        prefs.status = message
        return Result.failure()
    }

    private class Totals {
        var scanned = 0L
        var eligibleFiles = 0L
        var totalBytes = 0L
        var done = 0L
        var copied = 0
        var skipped = 0
        val progressTotal: Long get() = if (totalBytes > 0L) totalBytes else eligibleFiles
    }
}
