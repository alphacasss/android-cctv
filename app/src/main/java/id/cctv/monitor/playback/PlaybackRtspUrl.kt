package id.cctv.monitor.playback

import java.net.URLEncoder

object PlaybackRtspUrl {

    fun build(
        trackId: Int,
        user: String,
        pass: String,
        host: String,
        port: Int,
        startUtc: String,
        endUtc: String
    ): String {
        val encodedUser = encode(user)
        val encodedPass = encode(pass)
        val auth = if (encodedUser.isEmpty() && encodedPass.isEmpty()) "" else "$encodedUser:$encodedPass@"
        return "rtsp://$auth$host:$port/Streaming/tracks/$trackId" +
            "?starttime=$startUtc&endtime=$endUtc"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
