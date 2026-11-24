package id.cctv.monitor.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Meluncurkan installer APK setelah izin "install unknown apps" tersedia. */
class UpdateInstallActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(UpdateNotifications.EXTRA_PATH)
        val file = if (path.isNullOrBlank()) null else File(path)
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Berkas pembaruan tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(this, "Izinkan pemasangan APK untuk CCTV Monitor", Toast.LENGTH_LONG)
                .show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            finish()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            startActivity(install)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
