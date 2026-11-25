package id.cctv.monitor.playback

import android.content.Context
import id.cctv.monitor.net.Endpoint
import id.cctv.monitor.net.IsapiClient
import id.cctv.monitor.net.isapiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Unduh satu klip cloud-only ke cache, lalu hapus setelah player selesai. */
class PlaybackDownloader(private val cacheDir: File) {

    constructor(context: Context) : this(context.cacheDir)

    suspend fun download(
        clip: RecordingClip,
        endpoint: Endpoint,
        user: String,
        pass: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val client = IsapiClient(endpoint.httpBase, user, pass)
            val response = postDownload(client, requestXml(clip))
            if (response.code !in 200..299) throw IllegalStateException(isapiError(response))
            val downloadId = DOWNLOAD_ID.find(response.body)?.groupValues?.get(1)
                ?: throw IllegalStateException("Perangkat tidak mengembalikan downloadId rekaman.")

            val (code, bytes) = client.getBinary("$DOWNLOAD_PATH/$downloadId")
            if (code !in 200..299 || bytes.isEmpty()) {
                throw IllegalStateException("Gagal mengunduh rekaman (HTTP $code).")
            }
            val file = PlaybackCache.fileFor(cacheDir, clip)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            file
        }
    }

    /** Hapus isi folder cache; dipanggil saat player ditutup. */
    fun clear() {
        PlaybackCache.directory(cacheDir).listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    private fun postDownload(client: IsapiClient, body: String) =
        client.post(DOWNLOAD_PATH, body).let { response ->
            if (response.code == 404) client.post(DOWNLOAD_PATH_NO_SLASH, body) else response
        }

    private fun requestXml(clip: RecordingClip): String {
        if (clip.playbackUri.isBlank()) {
            throw IllegalStateException("Perangkat tidak mengembalikan playbackURI rekaman.")
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<downloadRequest version="1.0" xmlns="http://www.isapi.org/ver20/XMLSchema">
  <playbackURI>${escapeXml(clip.playbackUri)}</playbackURI>
</downloadRequest>"""
    }

    private fun escapeXml(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private companion object {
        const val DOWNLOAD_PATH = "/ISAPI/ContentMgmt/download"
        const val DOWNLOAD_PATH_NO_SLASH = "/ISAPI/ContentMgmt/download/"
        val DOWNLOAD_ID = Regex("<downloadId>\\s*([^<\\s]+)\\s*</downloadId>", RegexOption.IGNORE_CASE)
    }
}
