package chezz.testmessage

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives Direct Reply text sent through the notification's RemoteInput.
 *
 * The log line this writes is the answer to "does the reply channel still work
 * after the notification is gone?" — cancel the notification first, reply from
 * the reading app, and see whether the line appears anyway.
 */
class ReplyReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_CANCEL_AFTER_REPLY = "chezz.testmessage.CANCEL_AFTER_REPLY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotifSender.KEY_REPLY_TEXT)
            ?.toString()

        if (text.isNullOrEmpty()) {
            EventLog.add("reply received but carried no text — check the RemoteInput result key")
            return
        }

        val key = intent.getStringExtra(NotifSender.EXTRA_CONVERSATION_KEY) ?: ""
        val cancelAfter = intent.getBooleanExtra(EXTRA_CANCEL_AFTER_REPLY, false)

        val stillShowing = context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .isNotEmpty()

        EventLog.add("REPLY RECEIVED: \"$text\" (notification was ${if (stillShowing) "still showing" else "already cancelled"})")

        when {
            // Reposting a notification the tester deliberately cancelled would
            // resurrect it and muddy the result, so only echo while it is live.
            !stillShowing ->
                EventLog.add("reply channel still worked with no notification present")
            cancelAfter -> NotifSender.cancelById(context, key)
            else -> NotifSender.appendOwnReply(context, key, text)
        }
    }
}
