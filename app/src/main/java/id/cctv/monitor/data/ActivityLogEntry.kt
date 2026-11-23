package id.cctv.monitor.data

/** Satu baris catatan aksi kontrol. [at] adalah epoch milliseconds. */
data class ActivityLogEntry(
    val at: Long,
    val title: String,
    val detail: String
)
