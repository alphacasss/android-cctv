package id.cctv.monitor.playback

import id.cctv.monitor.control.CloudIsapiClient
import id.cctv.monitor.net.IsapiClient
import id.cctv.monitor.net.IsapiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface PlaybackIsapi {
    suspend fun post(path: String, body: String): IsapiResponse
}

class DirectPlaybackIsapi(private val client: IsapiClient) : PlaybackIsapi {
    override suspend fun post(path: String, body: String): IsapiResponse = withContext(Dispatchers.IO) {
        client.post(path, body)
    }
}

class CloudPlaybackIsapi(private val client: CloudIsapiClient) : PlaybackIsapi {
    override suspend fun post(path: String, body: String): IsapiResponse = client.post(path, body)
}
