package id.cctv.monitor.playback

import android.content.Context
import id.cctv.monitor.net.Endpoint
import id.cctv.monitor.net.IsapiClient
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
            val body = requestXml(clip)
            val file = PlaybackCache.fileFor(cacheDir, clip)
            var code = client.postToFile(DOWNLOAD_PATH, body, file)
            if (code == 404) code = client.postToFile(DOWNLOAD_PATH_NO_SLASH, body, file)
            if (code !in 200..299 || file.length() == 0L) {
                throw IllegalStateException("Gagal mengunduh rekaman (HTTP $code).")
            }
            file
        }
    }

    /** Hapus isi folder cache; dipanggil saat player ditutup. */
    fun clear() {
        PlaybackCache.directory(cacheDir).listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
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
    }
}
