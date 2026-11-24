package id.cctv.monitor.playback

import id.cctv.monitor.net.Endpoint
import id.cctv.monitor.net.IsapiResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.TimeZone

class PlaybackSearchRepositoryTest {
    private val endpoint = Endpoint("192.168.1.64", 80, 554, remote = false)

    @Test fun directFailure_fallsBackToCloud() {
        runBlocking {
            val cloud = FakeIsapi(listOf(200 to "<CMSearchResult><matchList/></CMSearchResult>"))
            val repo = PlaybackSearchRepository(FakeIsapi(emptyList()), cloud)
            val result = repo.search(101, 0L, TimeZone.getTimeZone("UTC"), endpoint).getOrThrow()
            assertFalse(result.directReachable)
            assertTrue(result.cloudAvailable)
            assertEquals(0, result.clips.size)
        }
    }

    @Test fun cloudMissing_returnsFailure() {
        runBlocking {
            val repo = PlaybackSearchRepository(FakeIsapi(emptyList()), null)
            assertTrue(repo.search(101, 0L, TimeZone.getTimeZone("UTC"), endpoint).isFailure)
        }
    }

    @Test fun directSuccess_staysOnDirect() {
        runBlocking {
            val cloud = FakeIsapi(emptyList())
            val repo = PlaybackSearchRepository(
                FakeIsapi(listOf(200 to "<CMSearchResult><matchList/></CMSearchResult>")),
                cloud
            )
            val result = repo.search(101, 0L, TimeZone.getTimeZone("UTC"), endpoint).getOrThrow()
            assertTrue(result.directReachable)
            assertFalse(result.cloudAvailable)
            assertEquals(endpoint, result.endpoint)
        }
    }

    private class FakeIsapi(private val responses: List<Pair<Int, String>>) : PlaybackIsapi {
        override suspend fun post(path: String, body: String): IsapiResponse {
            if (responses.isEmpty()) throw IOException("offline")
            val (code, xml) = responses.first()
            return IsapiResponse(code, xml)
        }
    }
}
