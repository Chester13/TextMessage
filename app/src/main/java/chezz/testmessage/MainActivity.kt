package chezz.testmessage

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Control panel. Every switch here maps to one variable in the notification that
 * a reading app might handle differently, so a suspected bug can be narrowed to
 * a single cause instead of a whole messaging app's behaviour.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_NOTIFICATIONS = 1
        /** Held statically so a scheduled re-post survives this Activity going away. */
        val handler = Handler(Looper.getMainLooper())
    }

    private lateinit var etSender: EditText
    private lateinit var etGroup: EditText
    private lateinit var etTitle: EditText
    private lateinit var etMessage: EditText
    private lateinit var etDelay: EditText
    private lateinit var etBackdate: EditText
    private lateinit var cbAutonumber: CheckBox
    private lateinit var cbLargeIcon: CheckBox
    private lateinit var cbPersonIcon: CheckBox
    private lateinit var cbContentIntent: CheckBox
    private lateinit var spCreatorBal: Spinner
    private lateinit var cbReply: CheckBox
    private lateinit var cbCancelAfterReply: CheckBox
    private lateinit var cbBumpTime: CheckBox
    private lateinit var tvLog: TextView

    private var counter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root_scroll).padForSystemBars()

        etSender = findViewById(R.id.et_sender)
        etGroup = findViewById(R.id.et_group)
        etTitle = findViewById(R.id.et_title)
        etMessage = findViewById(R.id.et_message)
        etDelay = findViewById(R.id.et_delay)
        etBackdate = findViewById(R.id.et_backdate)
        cbAutonumber = findViewById(R.id.cb_autonumber)
        cbLargeIcon = findViewById(R.id.cb_large_icon)
        cbPersonIcon = findViewById(R.id.cb_person_icon)
        cbContentIntent = findViewById(R.id.cb_content_intent)
        spCreatorBal = findViewById(R.id.sp_creator_bal)
        cbReply = findViewById(R.id.cb_reply)
        cbCancelAfterReply = findViewById(R.id.cb_cancel_after_reply)
        cbBumpTime = findViewById(R.id.cb_bump_time)
        tvLog = findViewById(R.id.tv_log)

        spCreatorBal.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            NotifSender.BalMode.entries.map { it.label }
        )

        NotifSender.ensureChannel(this)
        requestNotificationPermission()

        findViewById<Button>(R.id.btn_post).setOnClickListener {
            NotifSender.postNew(applicationContext, readConfig(bumpCounter = true))
        }
        findViewById<Button>(R.id.btn_repost).setOnClickListener {
            NotifSender.repost(applicationContext, readConfig(bumpCounter = false))
        }
        findViewById<Button>(R.id.btn_repost_delayed).setOnClickListener { scheduleRepost() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            NotifSender.cancel(applicationContext, readConfig(bumpCounter = false))
        }
        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            NotifSender.reset(applicationContext, readConfig(bumpCounter = false))
        }
        findViewById<Button>(R.id.btn_reset_all).setOnClickListener {
            NotifSender.resetAll(applicationContext)
        }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener { EventLog.clear() }
    }

    override fun onResume() {
        super.onResume()
        EventLog.onChanged = { runOnUiThread { tvLog.text = EventLog.text() } }
        tvLog.text = EventLog.text()
    }

    override fun onPause() {
        super.onPause()
        EventLog.onChanged = null
    }

    private fun scheduleRepost() {
        val seconds = etDelay.text.toString().toIntOrNull()?.coerceIn(1, 600) ?: 20
        // Snapshot the switches now: the delayed run uses what was set when it
        // was scheduled, not whatever the panel looks like when it fires.
        val config = readConfig(bumpCounter = false)
        val context = applicationContext
        handler.postDelayed({ NotifSender.repost(context, config) }, seconds * 1000L)
        EventLog.add("re-post scheduled in ${seconds}s")
        Toast.makeText(this, getString(R.string.delay_scheduled, seconds), Toast.LENGTH_SHORT).show()
    }

    private fun readConfig(bumpCounter: Boolean): NotifSender.Config {
        val sender = etSender.text.toString().trim().ifEmpty { "Alice" }
        val group = etGroup.text.toString().trim().takeIf { it.isNotEmpty() }
        val baseText = etMessage.text.toString().trim().ifEmpty { "Test message" }

        if (bumpCounter && cbAutonumber.isChecked) counter++
        val text = if (cbAutonumber.isChecked) "$baseText #$counter" else baseText

        return NotifSender.Config(
            senderName = sender,
            conversationTitle = group,
            // Empty override falls back to what most chat apps put in EXTRA_TITLE:
            // the group name for a group, otherwise the sender's name.
            contentTitle = etTitle.text.toString().trim().ifEmpty { group ?: sender },
            messageText = text,
            withLargeIcon = cbLargeIcon.isChecked,
            withPersonIcon = cbPersonIcon.isChecked,
            withContentIntent = cbContentIntent.isChecked,
            creatorBalMode = NotifSender.BalMode.entries[spCreatorBal.selectedItemPosition],
            withReplyAction = cbReply.isChecked,
            cancelAfterReply = cbCancelAfterReply.isChecked,
            bumpTimeOnRepost = cbBumpTime.isChecked,
            backdateSeconds = etBackdate.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0,
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.need_notification_permission, Toast.LENGTH_LONG).show()
        }
    }
}
