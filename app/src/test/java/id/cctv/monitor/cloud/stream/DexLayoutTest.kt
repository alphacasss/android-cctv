package id.cctv.monitor.cloud.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresi bug SDK di perangkat kedua: arsip hikclasses.zip berisi
 * `classes3.dex` + `classes12.dex`, tanpa `classes.dex`. DexClassLoader hanya
 * menjamin kelas dari dex bernama kanonik yang dimuat.
 */
class DexLayoutTest {

    @Test
    fun canonicalNames_memetakanSemuaDexKeNamaYangDikenaliClassLoader() {
        assertEquals(
            listOf(
                "classes3.dex" to "classes.dex",
                "classes12.dex" to "classes2.dex"
            ),
            DexLayout.canonicalNames(
                listOf("classes3.dex", "classes12.dex", "META-INF/MANIFEST.MF")
            )
        )
    }

    @Test
    fun canonicalNames_tidakMengubahTataLetakYangSudahKanonik() {
        assertEquals(
            listOf("classes.dex" to "classes.dex", "classes2.dex" to "classes2.dex"),
            DexLayout.canonicalNames(listOf("classes.dex", "classes2.dex"))
        )
    }
}
