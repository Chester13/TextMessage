package chezz.testmessage

import android.graphics.Insets
import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Pads a view clear of the status and navigation bars.
 *
 * From Android 15 an app targeting SDK 35 or above is always laid out edge to
 * edge, so without this the first field on a screen sits underneath the status
 * bar and the last one underneath the gesture bar.
 */
fun View.padForSystemBars() {
    // Captured once, before any inset has been applied, so repeated callbacks add
    // to the padding the layout asked for rather than to their own last result.
    val basePadding = Insets.of(paddingLeft, paddingTop, paddingRight, paddingBottom)

    setOnApplyWindowInsetsListener { view, windowInsets ->
        val bars = if (Build.VERSION.SDK_INT >= 30) {
            windowInsets.getInsets(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            Insets.of(
                windowInsets.systemWindowInsetLeft,
                windowInsets.systemWindowInsetTop,
                windowInsets.systemWindowInsetRight,
                windowInsets.systemWindowInsetBottom
            )
        }
        view.setPadding(
            basePadding.left + bars.left,
            basePadding.top + bars.top,
            basePadding.right + bars.right,
            basePadding.bottom + bars.bottom
        )
        windowInsets
    }
    requestApplyInsets()
}
