package id.cctv.monitor.cloud.stream

/**
 * Menentukan nama kanonik untuk setiap dex di arsip SDK.
 *
 * `hikclasses.zip` berisi `classes3.dex` dan `classes12.dex` (tanpa
 * `classes.dex`). Tidak semua versi ART menjamin kelas dari dex bernama
 * sembarang dimuat oleh `DexClassLoader`, jadi semua dex diekstrak ulang
 * menjadi `classes.dex`, `classes2.dex`, … sebelum dimuat.
 */
object DexLayout {

    private val DEX_NAME = Regex("classes(\\d*)\\.dex")

    /**
     * @return pasangan (nama asli di zip, nama kanonik) untuk setiap dex,
     *   diurutkan berdasarkan nomor aslinya.
     */
    fun canonicalNames(entries: List<String>): List<Pair<String, String>> =
        entries.mapNotNull { name ->
            val simpleName = name.substringAfterLast('/')
            val match = DEX_NAME.matchEntire(simpleName) ?: return@mapNotNull null
            val index = if (match.groupValues[1].isEmpty()) 0
            else match.groupValues[1].toIntOrNull()?.minus(1) ?: return@mapNotNull null
            name to index
        }
            .sortedBy { it.second }
            .mapIndexed { position, (original, _) -> original to canonicalName(position) }

    private fun canonicalName(index: Int): String =
        if (index == 0) "classes.dex" else "classes${index + 1}.dex"
}
