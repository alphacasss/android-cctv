package id.cctv.monitor.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Satu item media (foto snapshot atau rekaman video klip). */
data class MediaItem(
    val file: File,
    val name: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val cameraName: String
)

/** Menyimpan foto/klip ke galeri dan membagikannya. */
object MediaSaver {

    private const val ALBUM = "CCTV Monitor"

    fun snapshotsDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM)

    fun recordingsDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), ALBUM)

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun safeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "camera" }

    /**
     * Simpan JPEG ke Pictures/CCTV Monitor folder aplikasi dan MediaStore.
     * Mengembalikan file hasil.
     */
    fun saveSnapshot(context: Context, cameraName: String, bytes: ByteArray): File? {
        val name = "cctv_${safeName(cameraName)}_${stamp()}.jpg"
        val dir = snapshotsDir(context)
        dir.mkdirs()
        val file = File(dir, name)
        try {
            file.writeBytes(bytes)
        } catch (_: Throwable) {
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            try {
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } catch (_: Throwable) {
            }
        } else {
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null
            )
        }
        return file
    }

    /**
     * Salinan kecil di cache agar bisa langsung dibagikan lewat FileProvider
     * bila file belum berada di path yang diizinkan.
     */
    private fun cacheCopy(context: Context, name: String, bytes: ByteArray): File {
        val dir = File(context.cacheDir, "share")
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file
    }

    /** Salin rekaman dari folder aplikasi ke galeri (Movies/CCTV Monitor). */
    fun publishRecording(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$ALBUM"
                )
            }
            val resolver = context.contentResolver
            val uri = try {
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } catch (t: Throwable) {
                null
            }
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    return true
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                }
            }
        }
        MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf("video/mp2t"), null
        )
        return true
    }

    fun share(context: Context, file: File, mime: String) {
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (_: Throwable) {
            try {
                val shared = cacheCopy(context, file.name, file.readBytes())
                FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
            } catch (_: Throwable) {
                return
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Bagikan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Membaca seluruh file snapshot dan rekaman yang tersimpan di perangkat. */
    fun listAllMedia(context: Context): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val snapDir = snapshotsDir(context)
        if (snapDir.exists()) {
            snapDir.listFiles()?.filter { it.isFile && (it.extension.equals("jpg", true) || it.extension.equals("png", true)) }?.forEach { f ->
                list.add(toMediaItem(f, isVideo = false))
            }
        }
        val recDir = recordingsDir(context)
        if (recDir.exists()) {
            recDir.listFiles()?.filter { it.isFile && (it.extension.equals("ts", true) || it.extension.equals("mp4", true)) }?.forEach { f ->
                list.add(toMediaItem(f, isVideo = true))
            }
        }
        val shareDir = File(context.cacheDir, "share")
        if (shareDir.exists()) {
            shareDir.listFiles()?.filter { it.isFile && it.name.startsWith("cctv_") }?.forEach { f ->
                if (list.none { it.file.name == f.name }) {
                    val isVid = f.extension.equals("ts", true) || f.extension.equals("mp4", true)
                    list.add(toMediaItem(f, isVideo = isVid))
                }
            }
        }
        return list.sortedByDescending { it.lastModified }
    }

    private fun toMediaItem(file: File, isVideo: Boolean): MediaItem {
        val rawName = file.nameWithoutExtension
        val parts = rawName.split("_")
        val camName = if (parts.size >= 4) {
            // format: cctv_CAM-01_20261119_120000
            parts.subList(1, parts.size - 2).joinToString("_")
        } else if (parts.size >= 2) {
            parts[1]
        } else {
            "Kamera"
        }
        return MediaItem(
            file = file,
            name = file.name,
            isVideo = isVideo,
            sizeBytes = file.length(),
            lastModified = file.lastModified(),
            cameraName = camName
        )
    }

    fun deleteMedia(context: Context, file: File): Boolean {
        var deleted = false
        if (file.exists()) {
            deleted = file.delete()
        }
        val cacheFile = File(File(context.cacheDir, "share"), file.name)
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        return deleted
    }
}
