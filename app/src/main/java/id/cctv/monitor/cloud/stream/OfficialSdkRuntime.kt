package id.cctv.monitor.cloud.stream

import android.content.Context
import android.util.Log
import android.view.Surface
import dalvik.system.DexClassLoader
import id.cctv.monitor.cloud.CloudStreamSource
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Loader + inisialisasi sekali untuk DEX dan native library resmi Hik-Connect.
 *
 * Semua kelas SDK dimuat lewat satu [DexClassLoader], dan library native
 * di-load oleh `NativeApi.initSDKEx(null)` memakai classloader yang sama.
 * Ini penting karena JNI_OnLoad melakukan FindClass untuk kelas PlayM4.
 */
object OfficialSdkRuntime {

    private const val TAG = "OfficialSdkRuntime"
    private const val ASSET_ZIP = "official/hikclasses.zip"

    private val lock = Any()
    private val streamLock = Any()

    @Volatile
    private var ready = false

    private lateinit var loader: DexClassLoader
    private lateinit var nativeClass: Class<*>
    private lateinit var initClass: Class<*>
    private lateinit var streamCallbackClass: Class<*>
    private lateinit var mediaCallbackClass: Class<*>

    // Dipertahankan agar tidak di-GC; sebagian native state mengikat objek ini.
    private var globalManager: Any? = null
    private var playM4: Any? = null
    private var initError: String? = null

    fun initialize(context: Context): Boolean {
        if (ready) return true
        return synchronized(lock) {
            if (ready) return@synchronized true
            try {
                val app = context.applicationContext
                val zip = prepareDexZip(app)
                val dexOut = File(app.codeCacheDir, "hikdex").apply { mkdirs() }
                loader = DexClassLoader(
                    zip.absolutePath,
                    dexOut.absolutePath,
                    app.applicationInfo.nativeLibraryDir,
                    app.classLoader
                )
                nativeClass = Class.forName("com.ez.stream.NativeApi", true, loader)
                initClass = Class.forName("com.ez.stream.InitParam", true, loader)
                streamCallbackClass =
                    Class.forName("com.ez.player.EZStreamDataCallback", true, loader)
                mediaCallbackClass =
                    Class.forName("com.ez.player.EZMediaCallback", true, loader)

                val initResult = call("initSDKEx", null)
                Log.d(TAG, "initSDKEx=$initResult")

                // Sama seperti EZStreamClientManager.create() di aplikasi resmi.
                val taskMgrClass = Class.forName("com.ez.stream.EZTaskMgr", true, loader)
                val taskMgr = taskMgrClass.getDeclaredConstructor().newInstance()
                val managerClass =
                    Class.forName("com.ez.stream.EZStreamClientManager", true, loader)
                val ctor = managerClass.getDeclaredConstructor(taskMgrClass)
                ctor.isAccessible = true
                globalManager = ctor.newInstance(taskMgr)

                val playClass = Class.forName("org.MediaPlayer.PlayM4.Player", true, loader)
                playM4 = playClass.getMethod("getInstance").invoke(null)

                ready = true
                initError = null
                Log.d(TAG, "SDK resmi siap")
                true
            } catch (e: Throwable) {
                initError = "${e::class.java.simpleName}: ${e.message}"
                Log.w(TAG, "Inisialisasi SDK gagal: $initError", e)
                false
            }
        }
    }

    fun lastError(): String? = initError

    private fun prepareDexZip(context: Context): File {
        val zip = File(context.filesDir, "hikclasses.zip")
        val expected = try {
            context.assets.openFd(ASSET_ZIP).use { it.length }
        } catch (_: Exception) {
            -1L
        }
        if (!zip.exists() || (expected > 0 && zip.length() != expected)) {
            if (zip.exists()) zip.setWritable(true)
            context.assets.open(ASSET_ZIP).use { input ->
                FileOutputStream(zip).use { output -> input.copyTo(output) }
            }
        }
        // DexClassLoader menolak dex zip yang masih writable.
        zip.setReadable(true)
        zip.setWritable(false)
        return zip
    }

    private fun call(name: String, vararg args: Any?): Any? {
        if (!::nativeClass.isInitialized) throw IllegalStateException("SDK belum diinisialisasi")
        val method: Method = nativeClass.methods.firstOrNull {
            it.name == name && it.parameterCount == args.size
        } ?: throw NoSuchMethodError("NativeApi.$name/${args.size}")
        return method.invoke(null, *args)
    }

    /** Buka satu stream live. Mengembalikan handle native, atau melempar error. */
    fun openStream(
        context: Context,
        source: CloudStreamSource,
        verificationCode: String,
        channelSerial: String,
        clientSession: String,
        userId: String,
        surface: Surface,
        onData: (Int, ByteArray) -> Unit,
        onEvent: (String, Int, Int) -> Unit
    ): Long {
        if (!initialize(context)) {
            throw IllegalStateException("SDK resmi tidak tersedia: ${lastError()}")
        }
        // setTokens adalah state global native. Seluruh urutan pembukaan stream
        // diserialisasi supaya tile yang start bersamaan tidak saling menimpa
        // token sebelum native sempat memakainya di createPreviewHandle/start.
        return synchronized(streamLock) {
            val tokens = source.tokens.toTypedArray()
            call("setTokens", tokens)

            val param = initClass.getDeclaredConstructor().newInstance()
            fun set(name: String, value: Any?) {
                initClass.getField(name).set(param, value)
            }
            val p2pKey = source.publicKey ?: ByteArray(0)
            set("iStreamSource", 0)
            set("iStreamType", source.streamType)
            set("iChannelNumber", source.channel)
            set("iSmallStream", if (source.streamType == 2) 1 else 0)
            set("szDevSerial", source.serial)
            set("szChnlSerial", channelSerial.ifBlank { source.serial })
            set("szVtmIP", source.vtmHost)
            set("iVtmPort", source.vtmPort)
            set("szCloudServerIP", source.vtmHost)
            set("iCloudServerPort", source.vtmPort)
            set("szP2PLinkKey", p2pKey)
            set("usP2PKeyVer", source.keyVersion)
            set("vtduServerPublicKey", p2pKey)
            set("vtduServerKeyVersion", source.keyVersion)
            set("szClientSession", clientSession)
            set("szUserID", userId)
            set("szStreamToken", source.tokens.firstOrNull().orEmpty())
            set("szTicketToken", source.tokens.getOrNull(1).orEmpty())

            val handle = call("createPreviewHandle", param) as? Long ?: 0L
            if (handle == 0L) throw IllegalStateException("createPreviewHandle gagal")

            val mediaCallback = Proxy.newProxyInstance(loader, arrayOf(mediaCallbackClass)) { _, m, a ->
                val ints = a?.filterIsInstance<Int>() ?: emptyList()
                onEvent(m.name, ints.getOrNull(0) ?: 0, ints.getOrNull(1) ?: 0)
                null
            }
            call("setMediaCallback", handle, mediaCallback)

            if (verificationCode.isNotBlank()) {
                call("setSecretKey", handle, verificationCode)
            }

            val dataCallback = Proxy.newProxyInstance(loader, arrayOf(streamCallbackClass)) { _, m, a ->
                if (m.name == "onDataListener" && a != null && a.size >= 3) {
                    val bytes = a[1] as? ByteArray
                    val len = a[2] as? Int ?: 0
                    if (bytes != null && len > 0 && len <= bytes.size) {
                        onData(a[0] as? Int ?: 0, bytes.copyOf(len))
                    }
                }
                null
            }
            call("setStreamDataCallback", handle, dataCallback)
            call("setDisplayWindows", handle, surface, 0)
            call("start", handle)
            handle
        }
    }

    fun closeStream(handle: Long) {
        if (handle == 0L || !::nativeClass.isInitialized) return
        synchronized(streamLock) {
            try {
                call("stop", handle)
            } catch (e: Throwable) {
                Log.w(TAG, "stop handle=$handle gagal: ${e.message}")
            }
            try {
                call("destroyHandle", handle)
            } catch (e: Throwable) {
                Log.w(TAG, "destroy handle=$handle gagal: ${e.message}")
            }
        }
    }
}
