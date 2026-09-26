# TestMessage

A notification generator for testing notification-reading apps (TalkingBubble).

It posts synthetic `MessagingStyle` chat notifications where every interesting
variable is a switch, so a suspected bug can be pinned to one cause instead of
being tangled up with a real messaging app's behaviour.

Notifications are built with the framework APIs (`Notification.Builder`,
`Notification.MessagingStyle`, `android.app.Person`) rather than
`NotificationCompat`, so the extras layout is exactly what a real chat app
produces, with no support-library version differences in the way.

## Build

```sh
make apk          # ./gradlew assembleRelease  (signed with the debug key)
make install      # build + adb install -r
```

`targetSdk` is 36 and **must stay at 34 or above**. On Android 14+ a
`PendingIntent` creator targeting 34+ no longer grants background-activity-start
privileges by default — lowering `targetSdk` would silently hide the very
behaviour this app exists to reproduce.

## What each control does

**Conversation** — sender name, optional group name (empty = 1:1 chat), an
optional `EXTRA_TITLE` override, and the message text. `MessagingStyle` does not
write `EXTRA_TITLE`/`EXTRA_TEXT` itself; this app sets both, the way real chat
apps do, because readers that key off those extras drop notifications without
them. Leave the title override empty and it defaults to the group name, or the
sender's name for a 1:1 chat.

**Avatar source** — the two places a chat app can put the sender picture:

| Switch | Where it lands | Looks like |
| --- | --- | --- |
| `largeIcon` | `Notification.getLargeIcon()` | blue circle, letter **L** |
| Person icon | `MessagingStyle` sender `Person.getIcon()` | orange circle, letter **P** |

Turn on one at a time. Whichever letter shows up in the reading app tells you
which source it actually reads.

**Open-the-chat intent** — attaches a `contentIntent` pointing at `ChatActivity`.
If that screen appears, the launch was allowed; if nothing happens, it was
blocked. The mode picker sets the *creator* half of the background-activity-start
check:

| Mode | Value | Available |
| --- | --- | --- |
| off | no options bundle | always (the Android 14+ default: denied) |
| `ALLOWED` | 1 | API 34+, deprecated in 36 |
| `ALLOW_ALWAYS` | 3 | API 36+ |
| `ALLOW_IF_VISIBLE` | 4 | API 36+ |

`ALLOW_ALWAYS` and `ALLOW_IF_VISIBLE` are distinct values, not aliases for the
old `ALLOWED`. A mode the device is too old for is downgraded automatically and
the substitution is written to the event log, so a "the opt-in did nothing"
result is never caused silently by an unrecognised constant.

**Direct Reply** — attaches a `RemoteInput` action. Incoming replies are written
to the event log along with whether the notification was still showing when the
reply arrived.

**Backdate** — dates the message into the past while the notification still
posts now. A reader that stamps messages with the arrival time cannot tell the
difference; one that uses the sender's time will place the message earlier, so
this is how to test which of the two a reader does, and how it treats a message
that predates the current bubble session.

**Album** — sends several messages that all claim the same send time, one
notification update per message, the way a batch of photos arrives. Turn the
counter off and the texts match too, which is genuinely indistinguishable and
should collapse to one message; turn it on and they differ, so all of them
should survive. A reader that identifies messages by send time alone keeps only
the first either way.

**Post** — `Post new message` appends a message to the conversation.
`Re-post unchanged` keeps the same notification id, the same message list and
(unless you tick the bump option) the same last-message timestamp. That is the
refresh pattern Telegram-based clients produce, and the one duplicate detection
has to survive.

## Driving it from adb

Opening this app brings it to the front, which collapses the overlay panel a
reading app is being tested in — so anything that only happens while that panel
is open cannot be reached from the buttons. A broadcast can:

```sh
BC="adb shell am broadcast -n chezz.testmessage/.CommandReceiver -a"

$BC chezz.testmessage.POST                          # post, inheriting the panel's settings
$BC chezz.testmessage.POST -e text "hello"          # ...with given text (no auto-numbering)
$BC chezz.testmessage.POST -e sender Bob -e group Family
$BC chezz.testmessage.POST --ei backdate 300        # dated five minutes ago
$BC chezz.testmessage.POST --ez person_icon true --ez large_icon false
$BC chezz.testmessage.REPOST                        # re-post unchanged
$BC chezz.testmessage.ALBUM --ei count 3            # one shared timestamp
$BC chezz.testmessage.CANCEL                        # cancel, keep the conversation
$BC chezz.testmessage.RESET                         # reset every conversation
```

Everything not named on the command line comes from whatever the control panel
has on screen, saved the moment it changes. So set the switches once and drive
it from the shell — leaving the app first is not needed.

Overrides: `sender`, `group` (empty string means "not a group"), `title`, `text`
as strings; `backdate` and `count` as `--ei`; `large_icon`, `person_icon` and
`omit_icon` as `--ez`. Auto-numbering is shared between the panel and the shell,
so numbers never repeat.

**Events** — what happened outside this app: the contentIntent firing, replies
arriving. Everything is mirrored to logcat: `adb logcat -s TestMessage`.

## Test recipes

### Which avatar source does the reader use?

1. Tick `largeIcon` only → post → the bubble should show a blue **L**.
2. `Reset all conversations`, tick Person icon only, change the sender name (a
   reader may cache the avatar per sender) → post.
3. A blue **L** or a default avatar in step 2 means the reader only reads
   `largeIcon` and misses `Person.getIcon()`.

### Does the "open original app" button work, and whose fault is it if not?

Run on Android 14 or higher — on 13 and below the sender's privilege is granted
by default and everything passes regardless.

1. Creator mode `off` → post → tap the reader's open button.
   Nothing happens: the creator half is denied, which is the Android 14+ default
   for any real chat app that has not opted in.
2. Creator mode `ALLOW_ALWAYS` (or `ALLOWED` below API 36) → post → tap again.
   - `ChatActivity` appears → the creator half was the only thing missing, so a
     reader that also opts in on the sender side will work.
   - Still nothing → the sender side is blocking too, and the reader has to pass
     `ActivityOptions.setPendingIntentBackgroundActivityStartMode(...)` to
     `PendingIntent.send()`.
3. `ALLOW_IF_VISIBLE` answers a narrower question: whether a visible overlay
   window counts as "visible" for the purposes of the check.

### Do duplicates come from the sender or from the reader?

1. Post one message and let the reader record it.
2. Wait past the reader's duplicate-detection window (TalkingBubble keeps
   15 seconds in memory and 5 seconds in the database), then
   `Re-post unchanged`. Setting the delay field to 20s and using
   `Re-post unchanged after delay` does the waiting for you.
3. The same message appearing twice in the reader's history — with two different
   timestamps — means the reader is stamping messages with the notification's
   post time instead of the message's own time.
4. Ticking `Give the unchanged message a fresh timestamp on re-post` tests the
   other possibility: the sending app changed the message time, which defeats
   any dedup keyed on that timestamp.

### Does the reply channel survive the notification being cancelled?

1. Post a message with the reply action attached.
2. `Cancel notification (keep conversation)` — this removes the notification but
   leaves the `PendingIntent` alone.
3. Reply from the reading app.
4. A `REPLY RECEIVED ... already cancelled` line in the event log proves the
   reply channel still works with no notification present, so anything that
   breaks in a real chat app after cancelling is that app's own internal state,
   not an Android platform restriction.
