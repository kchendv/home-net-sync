package com.example.homenetsync

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileOutputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.Properties

internal object Smb {
    suspend fun test(host: String, share: String, user: String, password: String, domain: String, destination: String) {
        if (host.isBlank() || share.isBlank()) {
            throw IllegalArgumentException("Enter the server address and share name")
        }
        useShare(host, share, user, password, domain, timeoutSeconds = 12) { remote ->
            ensureDirectories(remote, cleanPath(destination))
        }
    }

    suspend fun <T> useShare(
        host: String,
        share: String,
        user: String,
        password: String,
        domain: String,
        timeoutSeconds: Long,
        block: suspend (SmbShare) -> T
    ): T {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB210")
            setProperty("jcifs.smb.lmCompatibility", "3") // NTLMv2 only
            setProperty("jcifs.smb.client.connTimeout", (timeoutSeconds * 1000).toString())
            setProperty("jcifs.smb.client.responseTimeout", (timeoutSeconds * 1000).toString())
            setProperty("jcifs.smb.client.allowGuestFallback", "false")
        }
        val baseContext = BaseContext(PropertyConfiguration(properties))
        val credentials = if (user.isBlank()) NtlmPasswordAuthenticator()
            else NtlmPasswordAuthenticator(domain.trim(), user.trim(), password)
        val context = baseContext.withCredentials(credentials)
        val rootUrl = "smb://${host.trim()}:${DEFAULT_PORT}/${encodePath(share.trim())}/"
        return try {
            block(SmbShare(rootUrl, context))
        } finally {
            context.close()
        }
    }

    fun ensureDirectories(remote: SmbShare, path: String) {
        var current = ""
        cleanPath(path).split('/').filter { it.isNotBlank() }.forEach { segment ->
            current = if (current.isEmpty()) segment else "$current/$segment"
            if (!remote.folderExists(current)) remote.mkdir(current)
        }
    }

    fun cleanPath(path: String): String = path.replace('\\', '/').trim().trim('/').split('/')
        .filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")

    fun join(first: String, second: String): String = listOf(cleanPath(first), cleanPath(second))
        .filter { it.isNotBlank() }.joinToString("/")

    private const val DEFAULT_PORT = 445

    internal fun encodePath(path: String): String = cleanPath(path).split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
}

internal class SmbShare(private val rootUrl: String, private val context: CIFSContext) {
    private fun file(path: String, directory: Boolean = false): SmbFile {
        val suffix = Smb.encodePath(path)
        val url = rootUrl + suffix + if (directory && suffix.isNotEmpty()) "/" else ""
        return SmbFile(url, context)
    }

    fun folderExists(path: String): Boolean = file(path, directory = true).let { it.exists() && it.isDirectory }

    fun mkdir(path: String) = file(path, directory = true).mkdir()

    fun fileExists(path: String): Boolean = file(path).exists()

    fun openOutput(path: String): OutputStream = SmbFileOutputStream(file(path), false)
}

