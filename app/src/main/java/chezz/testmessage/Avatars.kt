package chezz.testmessage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon

/**
 * Generated avatars for the two places a messaging app can put a picture.
 *
 * They are deliberately different colours and carry a different letter, so when
 * a bubble shows up you can tell at a glance which source the reading app picked:
 *
 *   blue "L"   -> taken from Notification.getLargeIcon()
 *   orange "P" -> taken from the MessagingStyle sender Person's icon
 *
 * 128x128 matches the size TalkingBubble's extractAvatar() clamps to, so nothing
 * in a test result can be blamed on rescaling.
 */
object Avatars {

    private const val SIZE = 128

    fun largeIcon(): Icon = Icon.createWithBitmap(render("L", Color.parseColor("#1E88E5")))

    fun personIcon(): Icon = Icon.createWithBitmap(render("P", Color.parseColor("#F4511E")))

    private fun render(letter: String, background: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, fill)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = SIZE * 0.6f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        // Centre on the glyph's own bounds rather than the font metrics: the two
        // letters then sit identically, which makes side-by-side screenshots clean.
        val bounds = Rect()
        text.getTextBounds(letter, 0, letter.length, bounds)
        canvas.drawText(letter, SIZE / 2f, SIZE / 2f + bounds.height() / 2f, text)

        return bitmap
    }
}
