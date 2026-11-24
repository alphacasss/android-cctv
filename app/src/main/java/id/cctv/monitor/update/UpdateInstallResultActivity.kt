package id.cctv.monitor.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/** Menerima hasil sesi PackageInstaller supaya kegagalan tidak samar-samar. */
class UpdateInstallResultActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "install status=$status detail=$detail")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = pendingConfirmation()
            if (confirmation != null) {
                // Jangan finish() di sini: dialog konfirmasi butuh activity induk yang hidup.
                startActivityForResult(confirmation, REQ_CONFIRM)
                return
            }
        }

        showResult(status, detail)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONFIRM) return
        // Status akhir dikirim ulang lewat PendingIntent ke activity ini.
        finish()
    }

    private fun showResult(status: Int, detail: String?) {
        Toast.makeText(
            this,
            InstallStatusMessage.forStatus(status, detail),
            Toast.LENGTH_LONG
        ).show()
        if (status == PackageInstaller.STATUS_SUCCESS) {
            UpdateNotifications.cancelReady(this)
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun pendingConfirmation(): Intent? =
        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

    private companion object {
        const val TAG = "UpdateInstall"
        const val REQ_CONFIRM = 9203
    }
}
