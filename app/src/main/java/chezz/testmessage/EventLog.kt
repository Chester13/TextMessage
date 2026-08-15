package chezz.testmessage

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app event log.
 *
 * The whole point of this app is observing what happens *outside* it — whether a
 * contentIntent actually launched, whether a reply actually arrived. Those events
 * fire in an Activity or a BroadcastReceiver, not in the control panel, so they
 * are collected here and rendered by [MainActivity].
 *
 * Everything is also mirrored to logcat under the [TAG] tag for `adb logcat -s`.
 */
object EventLog {

    const val TAG = "TestMessage"

    private const val MAX_ENTRIES = 60

    private val entries = ArrayList<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Set by [MainActivity] while it is visible so new events refresh the view. */
    var onChanged: (() -> Unit)? = null

    @Synchronized
    fun add(line: String) {
        Log.d(TAG, line)
        entries.add(0, "${stamp.format(Date())}  $line")
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        onChanged?.invoke()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        onChanged?.invoke()
    }

    @Synchronized
    fun text(): String =
        if (entries.isEmpty()) "(no events yet)" else entries.joinToString("\n")
}
