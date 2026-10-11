package app.seb3thehacker.gearslip

import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService

/** Silent, debug-only media session for checking the car UI and its transport controls. */
class BenchMediaService : MediaBrowserService() {
    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "Gearslip preview").apply {
            setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Sample track")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Preview player")
                .build())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = updatePlayback(PlaybackState.STATE_PLAYING)
                override fun onPause() = updatePlayback(PlaybackState.STATE_PAUSED)
            })
            isActive = true
        }
        updatePlayback(PlaybackState.STATE_PAUSED)
        sessionToken = session.sessionToken
    }

    private fun updatePlayback(state: Int) {
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(state, 0, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
            .build())
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? =
        if (clientUid == applicationInfo.uid) BrowserRoot("preview", null) else null

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        session.release()
        super.onDestroy()
    }
}
