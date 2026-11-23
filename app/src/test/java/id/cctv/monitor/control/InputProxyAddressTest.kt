package id.cctv.monitor.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresi bug "MATIKAN SEMUA RTSP": semua kanal diberi IP dummy yang sama
 * sehingga NVR menolak kanal 2-8 dengan ModifiedIpCannotBeDuplicate.
 */
class InputProxyAddressTest {

    @Test
    fun dummyIp_memberiAlamatUnikPerKanal() {
        val channels = 1..8
        val addresses = channels.map(InputProxyAddress::dummyIp)

        assertEquals(
            listOf(
                "192.0.2.1", "192.0.2.2", "192.0.2.3", "192.0.2.4",
                "192.0.2.5", "192.0.2.6", "192.0.2.7", "192.0.2.8"
            ),
            addresses
        )
        assertEquals(addresses.size, addresses.toSet().size)
    }

    @Test
    fun isDummy_mengenaliAlamatLegacyDanAlamatPerKanal() {
        assertTrue(InputProxyAddress.isDummy("192.0.2.1"))
        assertTrue(InputProxyAddress.isDummy("192.0.2.8"))
        assertFalse(InputProxyAddress.isDummy("192.168.100.26"))
        assertFalse(InputProxyAddress.isDummy(null))
    }

    @Test
    fun butuhTulisUlang_alamatLegacyKanalLain() {
        assertTrue(InputProxyAddress.needsRewrite("192.0.2.1", 3))
        assertTrue(InputProxyAddress.needsRewrite("192.0.2.8", 3))
        assertFalse(InputProxyAddress.needsRewrite("192.0.2.3", 3))
        assertFalse(InputProxyAddress.needsRewrite("192.168.100.26", 3))
        assertFalse(InputProxyAddress.needsRewrite(null, 3))
    }
}
