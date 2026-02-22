package fyi.acmc.cogpilot

import android.content.Context
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SpotifyManager(private val context: Context) {

    private val TAG = "SpotifyManager"
    private val clientId = BuildConfig.SPOTIFY_CLIENT_ID
    private val redirectUri = "fyi.acmc.cogpilot://spotify-callback"

    @Volatile private var appRemote: SpotifyAppRemote? = null

    private val PLAYLISTS = mapOf(
        "energetic"  to "spotify:playlist:37i9dQZF1DXdPec7aLTmlC",
        "uplifting"  to "spotify:playlist:37i9dQZF1DXdPec7aLTmlC",
        "upbeat"     to "spotify:playlist:37i9dQZF1DX0XUsuxWHRQd",
        "calm"       to "spotify:playlist:37i9dQZF1DWZeKCadgRdKQ",
        "chill"      to "spotify:playlist:37i9dQZF1DX4WYpdgoIcn6",
        "focus"      to "spotify:playlist:37i9dQZF1DWZeKCadgRdKQ",
        "driving"    to "spotify:playlist:37i9dQZF1DWToT4CWx0ciy",
        "pop"        to "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
        "wake up"    to "spotify:playlist:37i9dQZF1DX0XUsuxWHRQd",
        "sleepy"     to "spotify:playlist:37i9dQZF1DX4WYpdgoIcn6"
    )

    private suspend fun connect(): SpotifyAppRemote? {
        appRemote?.takeIf { it.isConnected }?.let { return it }

        if (!SpotifyAppRemote.isSpotifyInstalled(context)) {
            Log.w(TAG, "Spotify app is not installed on this device")
            return null
        }

        return suspendCancellableCoroutine { cont ->
            val params = ConnectionParams.Builder(clientId)
                .setRedirectUri(redirectUri)
                .showAuthView(true)
                .build()

            SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                override fun onConnected(remote: SpotifyAppRemote) {
                    appRemote = remote
                    Log.i(TAG, "✓ Spotify App Remote connected")
                    cont.resume(remote)
                }

                override fun onFailure(error: Throwable) {
                    Log.e(TAG, "✗ Spotify App Remote connection failed: ${error.message}")
                    cont.resume(null)
                }
            })
        }
    }

    fun disconnect() {
        appRemote?.let { SpotifyAppRemote.disconnect(it) }
        appRemote = null
    }

    suspend fun fadeOutAndPause(durationMs: Long = 2000L) {
        val remote = connect() ?: return
        
        val isPlaying = suspendCancellableCoroutine<Boolean> { cont ->
            remote.playerApi.playerState
                .setResultCallback { state ->
                    cont.resume(state.track != null && !state.isPaused)
                }
                .setErrorCallback { error ->
                    Log.e(TAG, "Failed reading player state to fade: ${error.message}")
                    cont.resume(false)
                }
        }
        
        if (!isPlaying) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val originalVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        if (originalVolume == 0) {
            remote.playerApi.pause()
            return
        }

        Log.i(TAG, "🎵 Fading out Spotify playback over ${durationMs}ms...")
        val steps = 20
        val stepDuration = durationMs / steps
        val volumeStep = originalVolume.toFloat() / steps

        try {
            for (i in 1..steps) {
                val newVol = (originalVolume - (volumeStep * i)).toInt().coerceAtLeast(0)
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVol, 0)
                kotlinx.coroutines.delay(stepDuration)
            }
            remote.playerApi.pause()
            Log.i(TAG, "🎵 Spotify paused.")
        } finally {
            // Restore volume immediately after pause
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    suspend fun getNowPlaying(): Result<String> {
        val remote = connect()
            ?: return Result.failure(Exception("Spotify app not available on this device"))

        return suspendCancellableCoroutine { cont ->
            remote.playerApi.playerState
                .setResultCallback { state ->
                    val track = state.track
                    if (track == null) {
                        cont.resume(Result.success("Nothing is currently playing"))
                    } else {
                        val paused = if (state.isPaused) " (paused)" else ""
                        cont.resume(Result.success(
                            "\"${track.name}\" by ${track.artist.name}$paused"
                        ))
                    }
                }
                .setErrorCallback { error ->
                    Log.e(TAG, "playerState error: ${error.message}")
                    cont.resume(Result.failure(error))
                }
        }
    }

    suspend fun play(query: String): Result<String> {
        val remote = connect()
            ?: return Result.failure(Exception("Spotify app not available on this device"))

        val lowerQuery = query.lowercase()
        val uri = PLAYLISTS.entries
            .firstOrNull { lowerQuery.contains(it.key) }?.value
            ?: PLAYLISTS["energetic"]!!

        return suspendCancellableCoroutine { cont ->
            remote.playerApi.play(uri)
                .setResultCallback {
                    Log.i(TAG, "▶ Playing: $uri for query='$query'")
                    cont.resume(Result.success("Now playing a $query playlist on Spotify"))
                }
                .setErrorCallback { error ->
                    Log.e(TAG, "play error: ${error.message}")
                    cont.resume(Result.failure(error))
                }
        }
    }
}
