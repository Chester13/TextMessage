package chezz.testmessage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import chezz.testmessage.ConfigStore.withOverrides

/**
 * Drives the app from adb, so a notification can be posted without touching the
 * screen.
 *
 * Tapping a button here brings this app to the front, which collapses the very
 * overlay panel a reading app is being tested in — so anything that only happens
 * while that panel is open was previously untestable. A broadcast leaves the
 * foreground alone.
 *
 * Settings come from whatever the control panel last had on screen; a command
 * overrides only the parts it names:
 *
 *     adb shell am broadcast -n chezz.testmessage/.CommandReceiver \
 *         -a chezz.testmessage.POST -e text "hello" -e group Family
 *
 * Exported so the shell can reach it. That is acceptable for a test tool whose
 * whole purpose is posting synthetic notifications — it grants no access to
 * anything else.
 */
class CommandReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_POST = "chezz.testmessage.POST"
        const val ACTION_REPOST = "chezz.testmessage.REPOST"
        const val ACTION_ALBUM = "chezz.testmessage.ALBUM"
        const val ACTION_CANCEL = "chezz.testmessage.CANCEL"
        const val ACTION_RESET = "chezz.testmessage.RESET"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        NotifSender.ensureChannel(app)

        val base = ConfigStore.load(app).withOverrides(intent)

        when (intent.action) {
            ACTION_POST -> NotifSender.postNew(app, base.copy(messageText = numbered(app, base, intent)))
            ACTION_REPOST -> NotifSender.repost(app, base)
            ACTION_ALBUM -> {
                // Numbered per entry when the text was not given, so the album's
                // messages differ; identical when it was, which is the other case
                // an album exists to exercise.
                val explicit = intent.getStringExtra("text")
                NotifSender.postAlbum(app, base) {
                    explicit ?: "${base.messageText} #${ConfigStore.nextCounter(app)}"
                }
            }
            ACTION_CANCEL -> NotifSender.cancel(app, base)
            ACTION_RESET -> NotifSender.resetAll(app)
            else -> EventLog.add("unknown command: ${intent.action}")
        }
    }

    /** Appends a counter unless the command supplied its own text. */
    private fun numbered(context: Context, config: NotifSender.Config, intent: Intent): String =
        if (intent.hasExtra("text")) config.messageText
        else "${config.messageText} #${ConfigStore.nextCounter(context)}"
}
