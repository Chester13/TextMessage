package chezz.testmessage

import android.content.Context
import android.content.Intent

/**
 * Persists the control panel's settings so a broadcast can reuse them.
 *
 * Driving the app from adb only becomes useful if the command line stays short,
 * and it stays short by inheriting whatever was last set on screen. So the panel
 * saves its state when it goes away, and a command overrides only what it names.
 */
object ConfigStore {

    private const val PREFS = "last_config"

    /**
     * Ceiling on the rebuild gap. The receiver that schedules the re-post keeps
     * itself alive with goAsync, and the platform allows that for about ten
     * seconds before it stops waiting.
     */
    const val MAX_REBUILD_GAP_MS = 9000
    private const val KEY_COUNTER = "counter"

    fun save(context: Context, config: NotifSender.Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("senderName", config.senderName)
            .putString("conversationTitle", config.conversationTitle ?: "")
            .putString("contentTitle", config.contentTitle)
            .putString("messageText", config.messageText)
            .putBoolean("withLargeIcon", config.withLargeIcon)
            .putBoolean("withPersonIcon", config.withPersonIcon)
            .putBoolean("withContentIntent", config.withContentIntent)
            .putBoolean("autoCancel", config.autoCancel)
            .putString("creatorBalMode", config.creatorBalMode.name)
            .putBoolean("withReplyAction", config.withReplyAction)
            .putBoolean("cancelAfterReply", config.cancelAfterReply)
            .putBoolean("rebuildAfterReply", config.rebuildAfterReply)
            .putInt("rebuildGapMs", config.rebuildGapMs)
            .putBoolean("bumpTimeOnRepost", config.bumpTimeOnRepost)
            .putInt("backdateSeconds", config.backdateSeconds)
            .putBoolean("omitIconOnThisMessage", config.omitIconOnThisMessage)
            .putInt("albumSize", config.albumSize)
            .putString("keyShape", config.keyShape.name)
            .putBoolean("newIdOnRepost", config.newIdOnRepost)
            .putBoolean("withGroupSummary", config.withGroupSummary)
            .putString("notificationGroup", config.notificationGroup)
            .apply()
    }

    fun load(context: Context): NotifSender.Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sender = p.getString("senderName", "Alice") ?: "Alice"
        val group = p.getString("conversationTitle", "")?.takeIf { it.isNotEmpty() }
        return NotifSender.Config(
            senderName = sender,
            conversationTitle = group,
            contentTitle = p.getString("contentTitle", null) ?: (group ?: sender),
            messageText = p.getString("messageText", "Test message") ?: "Test message",
            withLargeIcon = p.getBoolean("withLargeIcon", true),
            withPersonIcon = p.getBoolean("withPersonIcon", false),
            withContentIntent = p.getBoolean("withContentIntent", true),
            autoCancel = p.getBoolean("autoCancel", false),
            creatorBalMode = runCatching {
                NotifSender.BalMode.valueOf(p.getString("creatorBalMode", null) ?: "")
            }.getOrDefault(NotifSender.BalMode.OFF),
            withReplyAction = p.getBoolean("withReplyAction", true),
            cancelAfterReply = p.getBoolean("cancelAfterReply", false),
            rebuildAfterReply = p.getBoolean("rebuildAfterReply", false),
            rebuildGapMs = p.getInt("rebuildGapMs", 800),
            bumpTimeOnRepost = p.getBoolean("bumpTimeOnRepost", false),
            backdateSeconds = p.getInt("backdateSeconds", 0),
            omitIconOnThisMessage = p.getBoolean("omitIconOnThisMessage", false),
            albumSize = p.getInt("albumSize", 3),
            keyShape = runCatching {
                NotifSender.KeyShape.valueOf(p.getString("keyShape", null) ?: "")
            }.getOrDefault(NotifSender.KeyShape.ID_PER_CHAT),
            newIdOnRepost = p.getBoolean("newIdOnRepost", false),
            withGroupSummary = p.getBoolean("withGroupSummary", false),
            notificationGroup = p.getString("notificationGroup", null)?.takeIf { it.isNotEmpty() }
                ?: "MESSAGES",
        )
    }

    /** Current auto-numbering value without advancing it. */
    fun currentCounter(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COUNTER, 0)

    /** Next auto-numbering value, advanced and stored. */
    fun nextCounter(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = p.getInt(KEY_COUNTER, 0) + 1
        p.edit().putInt(KEY_COUNTER, next).apply()
        return next
    }

    /**
     * Applies whatever the command named, leaving the rest as the panel left it.
     *
     * `group` accepts an empty string to mean "not a group", which is otherwise
     * impossible to express — omitting it keeps the stored value instead.
     */
    fun NotifSender.Config.withOverrides(intent: Intent): NotifSender.Config {
        var out = this
        intent.getStringExtra("sender")?.let {
            out = out.copy(senderName = it, contentTitle = it)
        }
        if (intent.hasExtra("group")) {
            val group = intent.getStringExtra("group")?.takeIf { it.isNotEmpty() }
            // Title follows the group when there is one, matching how most chat
            // apps label a group notification.
            out = out.copy(conversationTitle = group, contentTitle = group ?: out.senderName)
        }
        intent.getStringExtra("title")?.let { out = out.copy(contentTitle = it) }
        intent.getStringExtra("text")?.let { out = out.copy(messageText = it) }
        if (intent.hasExtra("backdate")) {
            out = out.copy(backdateSeconds = intent.getIntExtra("backdate", 0))
        }
        if (intent.hasExtra("large_icon")) {
            out = out.copy(withLargeIcon = intent.getBooleanExtra("large_icon", true))
        }
        if (intent.hasExtra("auto_cancel")) {
            out = out.copy(autoCancel = intent.getBooleanExtra("auto_cancel", false))
        }
        if (intent.hasExtra("person_icon")) {
            out = out.copy(withPersonIcon = intent.getBooleanExtra("person_icon", false))
        }
        if (intent.hasExtra("omit_icon")) {
            out = out.copy(omitIconOnThisMessage = intent.getBooleanExtra("omit_icon", false))
        }
        if (intent.hasExtra("rebuild_after_reply")) {
            out = out.copy(rebuildAfterReply = intent.getBooleanExtra("rebuild_after_reply", false))
        }
        if (intent.hasExtra("gap")) {
            out = out.copy(rebuildGapMs = intent.getIntExtra("gap", 800).coerceIn(0, MAX_REBUILD_GAP_MS))
        }
        if (intent.hasExtra("count")) {
            out = out.copy(albumSize = intent.getIntExtra("count", 3))
        }
        // Named by enum constant rather than by index: the order is a UI detail and
        // a script pinned to it would break the day a shape is added in the middle.
        intent.getStringExtra("key_shape")?.let { name ->
            runCatching { NotifSender.KeyShape.valueOf(name.uppercase()) }
                .onSuccess { out = out.copy(keyShape = it) }
        }
        if (intent.hasExtra("new_id_on_repost")) {
            out = out.copy(newIdOnRepost = intent.getBooleanExtra("new_id_on_repost", false))
        }
        if (intent.hasExtra("group_summary")) {
            out = out.copy(withGroupSummary = intent.getBooleanExtra("group_summary", false))
        }
        intent.getStringExtra("notif_group")?.takeIf { it.isNotEmpty() }?.let {
            out = out.copy(notificationGroup = it)
        }
        return out
    }
}
