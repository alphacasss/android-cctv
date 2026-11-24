package id.cctv.monitor.update

import android.app.Activity
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

    private companion object {
        const val TAG = "UpdateInstall"
    }
}
