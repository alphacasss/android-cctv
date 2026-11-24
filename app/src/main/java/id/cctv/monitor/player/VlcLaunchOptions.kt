package id.cctv.monitor.player

/** Opsi global LibVLC. Satu tempat supaya semua pemanggil memakai nilai yang sama. */
internal object VlcLaunchOptions {

    /** 4 stream aktif tidak perlu decoder thread auto yang berlebih. */
    const val DECODER_THREADS = 2

    fun base(): ArrayList<String> = arrayListOf(
        "--rtsp-tcp",
        "--no-audio",
        "--no-video-title-show",
        "--network-caching=300",
        "--live-caching=300",
        "--drop-late-frames",
        "--skip-frames",
        "--avcodec-threads=$DECODER_THREADS"
    )
}
