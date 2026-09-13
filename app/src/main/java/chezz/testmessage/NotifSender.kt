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

    /**
     * How the posting app spreads its chats over notification ids and tags, which
     * is what decides the shape of `sbn.key` — "user|package|id|tag|uid" — and so
     * how stable that key is for one conversation over time.
     *
     * Worth varying because a reader that remembers a key in order to act on it
     * later behaves differently against each of these, and the differences only
     * show up on the app that uses that shape. Measured from real notifications:
     * WhatsApp puts everything under id 1 and separates chats by tag, LINE gives
     * each chat an id under one fixed tag, and Telegram-based clients give each
     * chat an id and no tag at all.
     */
    enum class KeyShape(val label: String) {
        ID_PER_CHAT("one id per chat, no tag — Telegram"),
        SHARED_ID_TAG_PER_CHAT("one shared id, tag per chat — WhatsApp"),
        ID_PER_CHAT_SHARED_TAG("one id per chat, one shared tag — LINE"),
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
        /**
         * Reproduces what a Telegram-based client does once a reply goes through:
         * mark that chat read, cancel every notification it has posted, and post
         * the still-unread ones again a moment later. A reader that treats an
         * app's cancel as "the user read this" loses the bubbles for chats the
         * user never touched, and the re-post arrives too late to bring them back.
         *
         * Supersedes [cancelAfterReply], which cancels only the chat replied to.
         */
        val rebuildAfterReply: Boolean,
        /**
         * How long a rebuild leaves the shade empty before posting again. This is
         * the variable that decides whether a reader notices the gap at all, and
         * the one thing a real client never lets you set.
         */
        val rebuildGapMs: Int,
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
        /** See [KeyShape]. Changing it re-posts the chat under a different key. */
        val keyShape: KeyShape,
        /**
         * Posts a group summary beside the chats, the way an app does once it has
         * more than one notification out.
         *
         * The summary is posted and then left alone: it is not updated or removed
         * when a chat's notification goes away. That is deliberate and is the
         * point of having it — what it makes observable is whether the *system*
         * takes an orphaned summary down on its own, which decides whether a
         * reader that cancels a chat has to deal with the leftover itself.
         */
        val withGroupSummary: Boolean,
        /**
         * Which notification group the chat joins, and therefore which summary
         * covers it. Two names means two groups out at once, which is what makes
         * "clearing one group must leave the other's summary alone" testable.
         */
        val notificationGroup: String,
    ) {
        fun personIconOnThisMessage(): Boolean = withPersonIcon && !omitIconOnThisMessage
    }

    private class Entry(
        val text: String,
        var time: Long,
        val fromSelf: Boolean,
        /** Per message, so one entry can lack a picture while its neighbours have one. */
        val withIcon: Boolean,
        /**
         * Who said it. Per message rather than per conversation, because a group
         * chat is one notification that several people speak into, and the platform
         * reads the latest message's sender to build the title it shows.
         */
        val senderName: String
    )

    private class Conversation(val id: Int) {
        val messages = ArrayList<Entry>()
        var lastConfig: Config? = null
        /** Read chats are the ones a rebuild drops instead of posting again. */
        var read = false
        /**
         * The tag and id the last post actually used. Cancelling reads these
         * rather than working them out again, so that changing the key shape
         * between posting and cancelling still takes down the right notification
         * instead of missing and leaving it on screen.
         */
        var postedTag: String? = null
        var postedId: Int = id
        /** Which notification group the last post put it in, for the summary count. */
        var postedGroup: String? = null
    }

    private val conversations = LinkedHashMap<String, Conversation>()
    private var nextId = 1000

    /** Gap between album updates; back-to-back notify() calls on one id get throttled. */
    private const val ALBUM_SPACING_MS = 400L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** What WhatsApp uses for every chat it posts. */
    private const val SHARED_ID = 1

    /** Stands in for LINE's NOTIFICATION_TAG_MESSAGE. */
    private const val SHARED_TAG = "TESTMESSAGE_TAG_MESSAGE"

    private const val SUMMARY_ID = 999

    private fun groupKeyFor(name: String) = "chezz.testmessage.$name"

    /**
     * One summary per group, told apart by tag.
     *
     * A real app's summary tends to share the id its chats use — WhatsApp's sits on
     * id 1 with no tag, beside chats on id 1 with one. That is not copied here,
     * because being able to have two groups out at once is worth more than matching
     * one app's summary key: it is the only way to check that cancelling everything
     * under one group leaves the other group's summary alone. Nothing reading these
     * notifications should care, since a summary is found by its flag and its group,
     * never by the id it happens to be on.
     */
    private fun summaryTargetFor(groupName: String): Pair<String?, Int> =
        "summary:$groupName" to SUMMARY_ID

    /**
     * What counts as one conversation, and so one notification.
     *
     * A group is identified by its own name, not by whoever last spoke in it: a
     * group chat is a single notification that several people post into, and the
     * platform builds the title it shows from the conversation title and the latest
     * message's sender. Keying on the speaker instead would split one chat into a
     * notification per person, which no client does and which hides the case where
     * one notification stands for several senders at once.
     */
    fun conversationKey(config: Config): String =
        config.conversationTitle ?: config.senderName

    /** The tag and id [config]'s shape wants for [convo]. See [KeyShape]. */
    private fun targetFor(config: Config, key: String, convo: Conversation): Pair<String?, Int> =
        when (config.keyShape) {
            KeyShape.ID_PER_CHAT -> null to convo.id
            // The separator is stripped out of the tag: sbn.key joins its fields with
            // it, so a tag carrying one reads in a log as though there were an extra
            // field, which is exactly the kind of thing to not be puzzling over while
            // reading a capture.
            KeyShape.SHARED_ID_TAG_PER_CHAT -> key.replace('|', '_') to SHARED_ID
            KeyShape.ID_PER_CHAT_SHARED_TAG -> SHARED_TAG to convo.id
        }

    /** Takes down whatever [convo] was last posted as, whatever the shape is now. */
    private fun cancelPosted(context: Context, convo: Conversation) {
        manager(context).cancel(convo.postedTag, convo.postedId)
    }

    /**
     * Posts the summary for the chats currently out.
     *
     * Refreshed alongside a chat so its count is not stale, and deliberately never
     * touched when one goes away — see [Config.withGroupSummary].
     */
    private fun postSummary(context: Context, config: Config) {
        val group = config.notificationGroup
        val members = conversations.values
            .filter { it.messages.isNotEmpty() && it.postedGroup == group }
        val (tag, id) = summaryTargetFor(group)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$group: ${members.size} chat(s)")
            .setContentText(members.joinToString(", ") { it.lastConfig?.senderName ?: "?" })
            .setGroup(groupKeyFor(group))
            .setGroupSummary(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
        manager(context).notify(tag, id, builder.build())
        EventLog.add("posted summary for \"$group\" (${members.size} chat(s))")
    }

    private fun cancelSummary(context: Context, groupName: String) {
        val (tag, id) = summaryTargetFor(groupName)
        manager(context).cancel(tag, id)
    }

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
        convo.read = false
        val sentAt = System.currentTimeMillis() - config.backdateSeconds * 1000L
        convo.messages.add(
            Entry(
                config.messageText, sentAt, fromSelf = false,
                withIcon = config.personIconOnThisMessage(), senderName = config.senderName
            )
        )
        post(context, key, convo, config)
        val dating = if (config.backdateSeconds > 0) ", dated ${config.backdateSeconds}s ago" else ""
        // The title is in here because it is the one field that decides how a reader
        // splits a group chat into senders, and the only way to tell "the panel did
        // not pick up my edit" from "the reader ignored it" is to see what was
        // actually sent.
        EventLog.add(
            "posted new message (id=${convo.id}, ${convo.messages.size} in style$dating)" +
                " title=\"${config.contentTitle}\": \"${config.messageText}\""
        )
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
                    Entry(
                        text, sentAt, fromSelf = false,
                        withIcon = config.personIconOnThisMessage(), senderName = config.senderName
                    )
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
        convo.messages.add(
            Entry(
                text, System.currentTimeMillis(), fromSelf = true,
                withIcon = false, senderName = config.senderName
            )
        )
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
        cancelPosted(context, convo)
        EventLog.add("cancelled notification (id=${convo.id}) — reply action left intact")
    }

    /**
     * Cancels everything this app has posted, then posts back whatever is still
     * unread once [gapMs] has passed.
     *
     * This is the refresh pattern of Telegram-based clients, and the reason a
     * reader cannot read "the app cancelled its notification" as "the user has
     * dealt with this": for a moment the app has nothing showing at all, and that
     * moment says nothing about the user. [readKey] names the chat the rebuild
     * treats as read, which is the one that does not come back.
     *
     * [onDone] fires once the re-post has happened. A broadcast receiver has to
     * hold itself open until then: its process is eligible to be killed the
     * moment onReceive returns, and a delayed re-post in a dead process simply
     * never happens — which looks exactly like the app choosing not to post
     * again, and would be read as a real result.
     */
    fun rebuild(context: Context, readKey: String?, gapMs: Long, onDone: (() -> Unit)? = null) {
        readKey?.let { conversations[it]?.read = true }
        val posted = conversations.filterValues { it.messages.isNotEmpty() }
        if (posted.isEmpty()) {
            EventLog.add("rebuild skipped: nothing posted yet")
            onDone?.invoke()
            return
        }
        posted.values.forEach { cancelPosted(context, it) }
        val readNote = readKey?.let { ", \"$it\" marked read" } ?: ""
        EventLog.add("rebuild: cancelled ${posted.size} notification(s)$readNote")

        handler.postDelayed({
            val back = posted.filterValues { !it.read }
            back.forEach { (key, convo) ->
                convo.lastConfig?.let { post(context, key, convo, it) }
            }
            EventLog.add("rebuild: re-posted ${back.size} of ${posted.size} after ${gapMs}ms")
            onDone?.invoke()
        }, gapMs)
    }

    /**
     * Resolves the conversation a command named. The name is the group's for a group
     * chat and the other person's for a one-to-one, which is the same thing a user
     * would call it.
     */
    fun conversationKeyForSender(name: String): String? =
        conversations.keys.firstOrNull { it == name }

    fun cancelById(context: Context, key: String) {
        val convo = conversations[key] ?: return
        cancelPosted(context, convo)
        EventLog.add("cancelled notification (id=${convo.postedId}) after reply")
    }

    fun reset(context: Context, config: Config) {
        val key = conversationKey(config)
        conversations.remove(key)?.let {
            cancelPosted(context, it)
            it.postedGroup?.let { group -> cancelSummary(context, group) }
            EventLog.add("reset conversation \"$key\" (id=${it.postedId})")
        }
    }

    fun resetAll(context: Context) {
        conversations.values.forEach { cancelPosted(context, it) }
        conversations.values.mapNotNull { it.postedGroup }.distinct()
            .forEach { cancelSummary(context, it) }
        conversations.clear()
        EventLog.add("reset all conversations")
    }

    // ── Building ──

    private fun post(context: Context, key: String, convo: Conversation, config: Config) {
        val self = Person.Builder().setName("Me").setKey("self").build()

        // Built per entry: a real client only attaches a picture once it has the
        // sender's avatar downloaded, so within one conversation some messages
        // carry one and some do not.
        fun senderFor(entry: Entry): Person {
            val builder = Person.Builder()
                .setName(entry.senderName)
                .setKey("sender:${entry.senderName}")
            if (entry.withIcon) builder.setIcon(Avatars.personIcon())
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
                    if (entry.fromSelf) null else senderFor(entry)
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

        if (config.withGroupSummary) builder.setGroup(groupKeyFor(config.notificationGroup))
        convo.postedGroup = config.notificationGroup

        val (tag, id) = targetFor(config, key, convo)
        // Remembered before posting, so a cancel later finds this notification even
        // if the shape has been changed on the panel in the meantime.
        convo.postedTag = tag
        convo.postedId = id
        manager(context).notify(tag, id, builder.build())

        if (config.withGroupSummary) postSummary(context, config)
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
            .putExtra(ReplyReceiver.EXTRA_REBUILD_AFTER_REPLY, config.rebuildAfterReply)
            .putExtra(ReplyReceiver.EXTRA_REBUILD_GAP_MS, config.rebuildGapMs)

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
