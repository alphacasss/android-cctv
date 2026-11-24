package id.cctv.monitor.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Meluncurkan sesi pemasangan APK setelah izin "install unknown apps" tersedia. */
class UpdateInstallActivity : Activity() {

    private var pendingFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Menyiapkan pemasangan…"
            gravity = Gravity.CENTER
        })
        val path = intent.getStringExtra(UpdateNotifications.EXTRA_PATH)
        val file = if (path.isNullOrBlank()) null else File(path)
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Berkas pembaruan tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        pendingFile = file

        if (canInstall()) {
            launchInstall(file)
        } else {
            requestInstallPermission()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_INSTALL_PERMISSION) return
        val file = pendingFile
        if (file == null || !file.exists()) {
            finish()
            return
        }
        if (canInstall()) {
            launchInstall(file)
        } else {
            Toast.makeText(
                this,
                "Izin pemasangan belum diberikan. Ketuk notifikasi pembaruan untuk mencoba lagi.",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun requestInstallPermission() {
        Toast.makeText(this, "Izinkan pemasangan APK untuk CCTV Monitor", Toast.LENGTH_LONG)
            .show()
        startActivityForResult(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            ),
            REQ_INSTALL_PERMISSION
        )
    }

    private fun launchInstall(file: File) {
        try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val sessionId = installer.createSession(params)
            val callbackIntent = Intent(this, UpdateInstallResultActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val callback = PendingIntent.getActivity(
                this,
                REQ_INSTALL_RESULT,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val session = installer.openSession(sessionId)
            session.use { s ->
                s.openWrite("cctv-update.apk", 0, file.length()).use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                s.commit(callback.intentSender)
            }
            Log.i(TAG, "sesi pemasangan $sessionId dikirim")
            // Sesi sudah menguasai berkas; activity ini boleh tutup supaya tidak menyisakan layar kosong.
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "gagal menyiapkan pemasangan", e)
            Toast.makeText(
                this,
                "Gagal menyiapkan pemasangan: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private companion object {
        const val TAG = "UpdateInstall"
        const val REQ_INSTALL_PERMISSION = 9201
        const val REQ_INSTALL_RESULT = 9202
    }
}
