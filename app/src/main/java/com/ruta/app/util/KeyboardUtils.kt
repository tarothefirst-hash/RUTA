package com.ruta.app.util

import android.app.Activity
import android.graphics.Rect
import android.view.View

/**
 * Adjusts view padding dynamically when the soft keyboard appears.
 * Solves soft input resize issues when adjustResize in AndroidManifest isn't responding.
 */
fun Activity.keepClearOfKeyboard(rootView: View) {
    rootView.viewTreeObserver.addOnGlobalLayoutListener {
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.rootView.height
        val keypadHeight = screenHeight - rect.bottom

        if (keypadHeight > screenHeight * 0.15) {
            // Keyboard is visible -> add bottom padding equal to keyboard height
            rootView.setPadding(0, 0, 0, keypadHeight)
        } else {
            // Keyboard is hidden -> reset padding
            rootView.setPadding(0, 0, 0, 0)
        }
    }
}