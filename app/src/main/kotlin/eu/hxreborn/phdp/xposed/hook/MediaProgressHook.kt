package eu.hxreborn.phdp.xposed.hook

import android.app.Notification
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification

object MediaProgressHook {
    private const val TICK_INTERVAL_MS = 500L
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var currentToken: MediaSession.Token? = null

    var onMediaProgress: ((Int) -> Unit)? = null
    var onMediaActiveChanged: ((Boolean) -> Unit)? = null

    private val ticker =
        object : Runnable {
            override fun run() {
                val ctrl = controller ?: return
                val state = ctrl.playbackState
                val meta = ctrl.metadata

                if (state != null && state.state == PlaybackState.STATE_PLAYING && meta != null) {
                    val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    if (duration > 0L) {
                        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                        val currentPos = state.position + (elapsed * state.playbackSpeed).toLong()
                        val pct =
                            ((currentPos.toFloat() / duration.toFloat()) * 100f)
                                .toInt()
                                .coerceIn(1, 100)
                        onMediaActiveChanged?.invoke(true)
                        onMediaProgress?.invoke(pct)
                    }
                    handler.postDelayed(this, TICK_INTERVAL_MS)
                } else {
                    onMediaActiveChanged?.invoke(false)
                    onMediaProgress?.invoke(0)
                }
            }
        }

    private val callback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                handler.removeCallbacks(ticker)
                if (state?.state == PlaybackState.STATE_PLAYING) {
                    handler.post(ticker)
                } else {
                    onMediaActiveChanged?.invoke(false)
                    onMediaProgress?.invoke(0)
                }
            }

            override fun onSessionDestroyed() {
                clear()
            }
        }

    @Suppress("DEPRECATION")
    fun inspectNotification(
        context: Context,
        sbn: StatusBarNotification,
    ) {
        val extras = sbn.notification?.extras ?: return
        val token =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION,
                    MediaSession.Token::class.java,
                )
            } else {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
            } ?: return

        if (token != currentToken) {
            controller?.unregisterCallback(callback)
            currentToken = token
            controller =
                MediaController(context, token).apply {
                    registerCallback(callback, handler)
                }
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun clear() {
        handler.removeCallbacks(ticker)
        controller?.unregisterCallback(callback)
        controller = null
        currentToken = null
        onMediaActiveChanged?.invoke(false)
        onMediaProgress?.invoke(0)
    }
}