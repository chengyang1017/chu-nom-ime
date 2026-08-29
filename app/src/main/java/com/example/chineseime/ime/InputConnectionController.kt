package com.example.chineseime.ime

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class InputConnectionController(private val connection: () -> InputConnection?) {
    fun commit(text: String) = connection()?.commitText(text, 1)
    fun setComposing(text: String) = connection()?.setComposingText(text, 1)
    fun finishComposing() = connection()?.finishComposingText()
    fun delete() { connection()?.deleteSurroundingText(1, 0) }
    fun enter(imeOptions: Int) {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection()?.performEditorAction(action)
        } else {
            connection()?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection()?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }
}