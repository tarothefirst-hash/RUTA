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

/**
 * Hides [views] (the bottom navigation bar) while the soft keyboard is open.
 *
 * With adjustResize the whole window shrinks above the keyboard, which lifts a bottom
 * navigation bar on top of the keyboard where it covers the field being typed into
 * (the white block over the profile screens). The bar has nothing to do while typing,
 * so it is hidden until the keyboard closes.
 */
fun Activity.hideWhileKeyboardOpen(root: View, vararg views: View) {
    root.viewTreeObserver.addOnGlobalLayoutListener {
        val rect = Rect()
        root.getWindowVisibleDisplayFrame(rect)
        val screenHeight = root.rootView.height
        val keyboardOpen = (screenHeight - rect.bottom) > screenHeight * 0.15
        val visibility = if (keyboardOpen) View.GONE else View.VISIBLE
        views.forEach { if (it.visibility != visibility) it.visibility = visibility }
    }
}
