package id.cctv.monitor.playback

import id.cctv.monitor.net.Endpoint
import id.cctv.monitor.net.IsapiResponse
import java.util.TimeZone

class PlaybackSearchRepository(
    private val direct: PlaybackIsapi?,
    private val cloud: PlaybackIsapi?
) {
    suspend fun search(
        trackId: Int,
        dayMillis: Long,
        zone: TimeZone,
        endpoint: Endpoint
    ): Result<PlaybackSearchResult> {
        val (dayStart, dayEnd) = PlaybackTime.dayRange(dayMillis, zone)
        val startUtc = PlaybackTime.utcStamp(dayStart)
        val endUtc = PlaybackTime.utcStamp(dayEnd)
        val body = PlaybackSearchXml.request(
            trackId,
            PlaybackTime.xmlStamp(dayStart),
            PlaybackTime.xmlStamp(dayEnd)
        )

        val directResponse = trySearch(direct, body)
        if (directResponse != null) {
            return Result.success(
                PlaybackSearchResult(
                    clips = PlaybackSearchXml.parse(directResponse.body),
                    directReachable = true,
                    cloudAvailable = false,
                    endpoint = endpoint,
                    startUtc = startUtc,
                    endUtc = endUtc
                )
            )
        }

        val cloudResponse = trySearch(cloud, body)
        if (cloudResponse != null) {
            return Result.success(
                PlaybackSearchResult(
                    clips = PlaybackSearchXml.parse(cloudResponse.body),
                    directReachable = false,
                    cloudAvailable = true,
                    endpoint = endpoint,
                    startUtc = startUtc,
                    endUtc = endUtc
                )
            )
        }

        return Result.failure(IllegalStateException("Tidak ada rute ISAPI untuk pencarian rekaman."))
    }

    private suspend fun trySearch(api: PlaybackIsapi?, body: String): IsapiResponse? {
        api ?: return null
        for (path in SEARCH_PATHS) {
            val response = try {
                api.post(path, body)
            } catch (e: Exception) {
                null
            } ?: continue
            if (response.code in 200..299) return response
            if (response.code != 404) return null
        }
        return null
    }

    private companion object {
        val SEARCH_PATHS = listOf(
            "/ISAPI/ContentMgmt/search/",
            "/ISAPI/ContentMgmt/search"
        )
    }
}
