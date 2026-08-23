package chezz.testmessage

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Builds and posts MessagingStyle notifications with every interesting variable
 * exposed as a switch, so a reading app (TalkingBubble) can be probed one
 * variable at a time.
 *
 * Notifications are built with the framework APIs rather than NotificationCompat
 * so the extras layout is exactly what a real messaging app produces.
 */
object NotifSender {

    const val CHANNEL_ID = "test_messages"
    const val KEY_REPLY_TEXT = "key_reply_text"
    const val EXTRA_CONVERSATION_KEY = "chezz.testmessage.CONVERSATION_KEY"

    /**
     * How the *creator* side of the background-activity-start check is set on the
     * contentIntent.
     *
     * Android 14 introduced ALLOWED (=1). Android 16 deprecated it and split the
     * meaning in two: ALLOW_ALWAYS (=3) is unconditional, ALLOW_IF_VISIBLE (=4)
     * only grants the privilege while the creator is visible. They are distinct
     * values, not aliases, so which one a reader needs is worth testing directly.
     *
     * OFF attaches no options bundle at all — the Android 14+ default, which is
     * "no privilege granted".
     */
    enum class BalMode(val label: String, val value: Int, val minApi: Int) {
        OFF("off (Android 14+ default: denied)", -1, 0),
        ALLOWED("ALLOWED — deprecated in 16", 1, 34),
        ALLOW_ALWAYS("ALLOW_ALWAYS", 3, 36),
        ALLOW_IF_VISIBLE("ALLOW_IF_VISIBLE", 4, 36),
    }

    /** Everything the control panel can vary for one posted notification. */
    data class Config(
        val senderName: String,
        /** Non-null marks this as a group chat and fills EXTRA_CONVERSATION_TITLE. */
        val conversationTitle: String?,
        /** Written to EXTRA_TITLE. Readers that key off the title need this. */
        val contentTitle: String,
        val messageText: String,
        val withLargeIcon: Boolean,
        val withPersonIcon: Boolean,
        val withContentIntent: Boolean,
        /**
         * Sets FLAG_AUTO_CANCEL, which makes the system remove the notification
         * when it is tapped — the only way to produce REASON_CLICK for a reader
         * watching removals. Off by default because it is the minority behaviour
         * among chat apps: WhatsApp leaves the flag off and clears its own
         * notification once the chat is opened, which arrives as REASON_APP_CANCEL
         * instead. Needs [withContentIntent], since the tap is what triggers it.
         */
        val autoCancel: Boolean,
        val creatorBalMode: BalMode,
        val withReplyAction: Boolean,
        val cancelAfterReply: Boolean,
        /** On re-post, give the unchanged message a fresh timestamp. */
        val bumpTimeOnRepost: Boolean,
        /**
         * Dates the message this many seconds into the past while the notification
         * itself still posts now, reproducing delivery latency or an offline
         * backlog. Readers that stamp messages with the arrival time cannot tell
         * the difference; readers that use the sender's time can.
         */
        val backdateSeconds: Int,
        /**
         * Withholds the Person icon from the message being posted while leaving it
         * on the ones already in the conversation. Reproduces a group member whose
         * avatar the client has not downloaded, which is where a reader that falls
         * through to an earlier entry ends up showing the wrong person's face.
         */
        val omitIconOnThisMessage: Boolean,
        /** How many messages `postAlbum` sends under a single shared timestamp. */
        val albumSize: Int,
    ) {
        fun personIconOnThisMessage(): Boolean = withPersonIcon && !omitIconOnThisMessage
    }

    private class Entry(
        val text: String,
        var time: Long,
        val fromSelf: Boolean,
        /** Per message, so one entry can lack a picture while its neighbours have one. */
        val withIcon: Boolean
    )

    private class Conversation(val id: Int) {
        val messages = ArrayList<Entry>()
        var lastConfig: Config? = null
    }

    private val conversations = LinkedHashMap<String, Conversation>()
    private var nextId = 1000

    /** Gap between album updates; back-to-back notify() calls on one id get throttled. */
    private const val ALBUM_SPACING_MS = 400L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun conversationKey(config: Config): String =
        "${config.senderName}|${config.conversationTitle ?: ""}"

    private fun conversationOf(key: String): Conversation =
        conversations.getOrPut(key) { Conversation(nextId++) }

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.channel_desc) }
        manager(context).createNotificationChannel(channel)
    }

    // ── Actions the control panel drives ──

    /** Appends a new incoming message to the conversation and posts it. */
    fun postNew(context: Context, config: Config) {
        val key = conversationKey(config)
        val convo = conversationOf(key)
        convo.lastConfig = config
        val sentAt = System.currentTimeMillis() - config.backdateSeconds * 1000L
        convo.messages.add(
            Entry(config.messageText, sentAt, fromSelf = false, withIcon = config.personIconOnThisMessage())
        )
        post(context, key, convo, config)
        val dating = if (config.backdateSeconds > 0) ", dated ${config.backdateSeconds}s ago" else ""
        EventLog.add("posted new message (id=${convo.id}, ${convo.messages.size} in style$dating): \"${config.messageText}\"")
    }

    /**
     * Sends several messages that all claim the same send time, one notification
     * update per message, the way a Telegram album of photos arrives.
     *
     * Each update is a separate callback for the reader, but every message shares
     * a timestamp — so a reader whose duplicate detection keys on time alone will
     * record the first and silently discard the rest. With the counter switched
     * off the texts match too, which is genuinely indistinguishable and should
     * collapse to one; with it on they differ and all of them should survive.
     *
     * The updates are spaced out because the framework throttles rapid repeats of
     * the same notification id.
     */
    fun postAlbum(context: Context, config: Config, textFor: (Int) -> String) {
        val key = conversationKey(config)
        val convo = conversationOf(key)
        convo.lastConfig = config
        val sentAt = System.currentTimeMillis() - config.backdateSeconds * 1000L
        val count = config.albumSize.coerceIn(2, 10)
        for (i in 0 until count) {
            handler.postDelayed({
                // Once only: the caller may be advancing a counter inside it.
                val text = textFor(i)
                convo.messages.add(
                    Entry(text, sentAt, fromSelf = false, withIcon = config.personIconOnThisMessage())
                )
                post(context, key, convo, config)
                EventLog.add("album ${i + 1}/$count at shared time $sentAt: \"$text\"")
            }, i * ALBUM_SPACING_MS)
        }
    }

    /**
     * Re-posts the conversation unchanged: same notification id, same message
     * list, same last-message timestamp unless [Config.bumpTimeOnRepost] is set.
     *
     * This is the shape Telegram-based clients produce when they refresh a
     * notification that is already showing, and it is what a reader's duplicate
     * detection has to survive.
     */
    fun repost(context: Context, config: Config) {
        val key = conversationKey(config)
        val convo = conversations[key]
        if (convo == null || convo.messages.isEmpty()) {
            EventLog.add("re-post skipped: no conversation yet for \"$key\" — post a message first")
            return
        }
        val effective = convo.lastConfig ?: config
        val last = convo.messages.last()
        if (config.bumpTimeOnRepost) {
            last.time = System.currentTimeMillis()
        }
        post(context, key, convo, effective)
        val note = if (config.bumpTimeOnRepost) "timestamp bumped" else "timestamp unchanged"
        EventLog.add("re-posted unchanged (id=${convo.id}, $note): \"${last.text}\"")
    }

    /** Appends the user's own reply, the way a messaging app echoes it back. */
    fun appendOwnReply(context: Context, key: String, text: String) {
        val convo = conversations[key] ?: return
        val config = convo.lastConfig ?: return
        convo.messages.add(Entry(text, System.currentTimeMillis(), fromSelf = true, withIcon = false))
        post(context, key, convo, config)
        EventLog.add("notification updated to include own reply: \"$text\"")
    }

    fun cancel(context: Context, config: Config) {
        val key = conversationKey(config)
        val convo = conversations[key]
        if (convo == null) {
            EventLog.add("cancel skipped: no conversation yet for \"$key\"")
            return
        }
        manager(context).cancel(convo.id)
        EventLog.add("cancelled notification (id=${convo.id}) — reply action left intact")
    }

    fun cancelById(context: Context, key: String) {
        val convo = conversations[key] ?: return
        manager(context).cancel(convo.id)
        EventLog.add("cancelled notification (id=${convo.id}) after reply")
    }

    fun reset(context: Context, config: Config) {
        val key = conversationKey(config)
        conversations.remove(key)?.let {
            manager(context).cancel(it.id)
            EventLog.add("reset conversation \"$key\" (id=${it.id})")
        }
    }

    fun resetAll(context: Context) {
        conversations.values.forEach { manager(context).cancel(it.id) }
        conversations.clear()
        EventLog.add("reset all conversations")
    }

    // ── Building ──

    private fun post(context: Context, key: String, convo: Conversation, config: Config) {
        val self = Person.Builder().setName("Me").setKey("self").build()

        // Built per entry: a real client only attaches a picture once it has the
        // sender's avatar downloaded, so within one conversation some messages
        // carry one and some do not.
        fun senderFor(withIcon: Boolean): Person {
            val builder = Person.Builder()
                .setName(config.senderName)
                .setKey("sender:${config.senderName}")
            if (withIcon) builder.setIcon(Avatars.personIcon())
            return builder.build()
        }

        val style = Notification.MessagingStyle(self)
        if (config.conversationTitle != null) {
            style.conversationTitle = config.conversationTitle
            style.isGroupConversation = true
        }
        convo.messages.forEach { entry ->
            // A null Person marks the message as sent by the user. Readers use
            // this to skip echoing the user's own replies back as new messages.
            style.addMessage(
                Notification.MessagingStyle.Message(
                    entry.text,
                    entry.time,
                    if (entry.fromSelf) null else senderFor(entry.withIcon)
                )
            )
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            // MessagingStyle does not write EXTRA_TITLE/EXTRA_TEXT itself, and
            // readers that key off those extras drop the notification without
            // them. Real messaging apps set both, so this app does too.
            .setContentTitle(config.contentTitle)
            .setContentText(convo.messages.last().text)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(config.autoCancel)
            .setCategory(Notification.CATEGORY_MESSAGE)

        if (config.withLargeIcon) builder.setLargeIcon(Avatars.largeIcon())

        if (config.withContentIntent) {
            builder.setContentIntent(buildContentIntent(context, key, convo, config))
        }
        if (config.withReplyAction) {
            builder.addAction(buildReplyAction(context, key, convo, config))
        }

        manager(context).notify(convo.id, builder.build())
    }

    private fun buildContentIntent(
        context: Context,
        key: String,
        convo: Conversation,
        config: Config
    ): PendingIntent {
        val mode = effectiveBalMode(config.creatorBalMode)

        val intent = Intent(context, ChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_CONVERSATION_KEY, key)
            .putExtra(ChatActivity.EXTRA_SENDER, config.senderName)
            .putExtra(ChatActivity.EXTRA_CREATOR_BAL_MODE, mode.name)

        // A PendingIntent's identity ignores the options bundle, so switching
        // modes under the same request code would silently reuse the old one.
        // Folding the mode into the request code keeps the variants distinct.
        val requestCode = convo.id * 10 + mode.ordinal

        val options: android.os.Bundle? =
            if (mode == BalMode.OFF) {
                null
            } else {
                ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(mode.value)
                    .toBundle()
            }

        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options
        )
    }

    /**
     * Downgrades a mode this device is too old to understand. Passing an unknown
     * constant would be silently ignored by the platform, which looks exactly
     * like "the opt-in did not work" and would poison a test result.
     */
    private fun effectiveBalMode(requested: BalMode): BalMode {
        if (Build.VERSION.SDK_INT >= requested.minApi) return requested
        val fallback = when {
            Build.VERSION.SDK_INT >= BalMode.ALLOWED.minApi -> BalMode.ALLOWED
            else -> BalMode.OFF
        }
        EventLog.add(
            "creator BAL mode ${requested.name} needs API ${requested.minApi}, " +
                "this device is API ${Build.VERSION.SDK_INT} — using ${fallback.name}"
        )
        return fallback
    }

    private fun buildReplyAction(
        context: Context,
        key: String,
        convo: Conversation,
        config: Config
    ): Notification.Action {
        val intent = Intent(context, ReplyReceiver::class.java)
            .setAction("chezz.testmessage.REPLY")
            .putExtra(EXTRA_CONVERSATION_KEY, key)
            .putExtra(ReplyReceiver.EXTRA_CANCEL_AFTER_REPLY, config.cancelAfterReply)

        // FLAG_MUTABLE is mandatory: the system fills the reply text into this
        // intent before delivering it.
        val pending = PendingIntent.getBroadcast(
            context,
            convo.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.reply_label))
            .build()

        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_notification),
            context.getString(R.string.reply_label),
            pending
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
