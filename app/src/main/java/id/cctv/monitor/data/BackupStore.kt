package id.cctv.monitor.data

import android.content.Context
import java.io.File

enum class BackupKind(val prefix: String) {
    PRIVACY("privmask"),
    STREAM("stream"),
    RTSP("rtsp"),
    NVR("nvr")
}

/** Backup XML asli kamera, disimpan di penyimpanan internal aplikasi. */
class BackupStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "backups")

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    fun file(cameraId: Int, kind: BackupKind): File =
        File(dir, "${kind.prefix}-%02d.xml".format(cameraId))

    fun exists(cameraId: Int, kind: BackupKind): Boolean =
        file(cameraId, kind).isFile

    fun read(cameraId: Int, kind: BackupKind): String? =
        file(cameraId, kind).takeIf { it.isFile }?.readText()

    fun write(cameraId: Int, kind: BackupKind, xml: String) {
        val target = file(cameraId, kind)
        target.parentFile?.mkdirs()
        target.writeText(xml)
    }

    fun delete(cameraId: Int, kind: BackupKind) {
        file(cameraId, kind).delete()
    }

    fun count(): Int = xmlFiles().size

    fun hasAny(): Boolean = xmlFiles().isNotEmpty()

    fun listNames(): List<String> = xmlFiles().map { it.name }

    fun clear() {
        xmlFiles().forEach { it.delete() }
    }

    private fun xmlFiles(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.extension.equals("xml", true) } ?: emptyList()
}
