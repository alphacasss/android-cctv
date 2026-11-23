package id.cctv.monitor.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Menyimpan sesi login Hik-Connect dan preferensi akun di penyimpanan
 * terenkripsi. Bila perangkat bermasalah dengan Android Keystore, kelas ini
 * jatuh ke SharedPreferences biasa dan menandai [encrypted] = false — sama
 * seperti [id.cctv.monitor.data.AppPrefs].
 */
class CloudAuthManager(private val context: Context) {

    private val prefs: SharedPreferences
    var encrypted: Boolean = true
        private set

    private val _session = MutableStateFlow<CloudSession?>(null)
    val session: StateFlow<CloudSession?> = _session.asStateFlow()

    val isLoggedIn: Boolean get() = _session.value != null

    init {
        prefs = try {
            val master = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE_ENCRYPTED,
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            encrypted = false
            context.applicationContext.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
        }
        _session.value = readSession()
    }

    /** Email terakhir yang berhasil login (untuk mengisi ulang form). */
    val email: String
        get() = prefs.getString(KEY_EMAIL, "").orEmpty()

    fun rememberEmail(value: String) {
        prefs.edit().putString(KEY_EMAIL, value.trim()).apply()
    }

    /** Simpan sesi login ke penyimpanan terenkripsi. */
    suspend fun saveSession(session: CloudSession) = withContext(Dispatchers.IO) {
        prefs.edit().apply {
            putString(KEY_ACCESS, session.accessToken)
            putString(KEY_REFRESH, session.refreshToken)
            putLong(KEY_EXPIRES_AT, session.expiresAt)
            putString(KEY_USER, session.userId)
            putString(KEY_API_DOMAIN, session.apiDomain)
            apply()
        }
        _session.value = session
    }

    /** Baca ulang sesi dari disk (dipakai saat aplikasi dibuka). */
    suspend fun loadSession(): CloudSession? = withContext(Dispatchers.IO) {
        readSession().also { _session.value = it }
    }

    suspend fun clearSession() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_USER)
            .remove(KEY_API_DOMAIN)
            .apply()
        _session.value = null
    }

    /** Domain API region yang dipakai akun ini (tanpa skema). */
    fun apiDomain(): String =
        _session.value?.apiDomain
            ?: prefs.getString(KEY_API_DOMAIN, DEFAULT_DOMAIN).orEmpty()
                .ifBlank { DEFAULT_DOMAIN }

    private fun readSession(): CloudSession? {
        val token = prefs.getString(KEY_ACCESS, null)
        val refresh = prefs.getString(KEY_REFRESH, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val userId = prefs.getString(KEY_USER, null)
        if (token.isNullOrBlank() || refresh.isNullOrBlank() || expiresAt <= 0L) return null
        return CloudSession(
            accessToken = token,
            refreshToken = refresh,
            expiresIn = 0L,
            userId = userId.orEmpty(),
            expiresAt = expiresAt,
            apiDomain = prefs.getString(KEY_API_DOMAIN, DEFAULT_DOMAIN).orEmpty()
                .ifBlank { DEFAULT_DOMAIN }
        )
    }

    companion object {
        const val DEFAULT_DOMAIN = "api.hik-connect.com"

        private const val FILE_ENCRYPTED = "cloud_auth_prefs"
        private const val FILE_FALLBACK = "cloud_auth_fallback"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER = "user_id"
        private const val KEY_API_DOMAIN = "api_domain"
        private const val KEY_EMAIL = "account_email"
    }
}
