package io.github.novatv.play.ui

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** The TV text box keeps Left/Right for the cursor and lets Up, Down and Back take the remote elsewhere. */
class FieldKeyTest {
    @Test fun upAndDownLeaveTheBoxOnPress() {
        assertEquals(FieldKey.UP, fieldKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN))
        assertEquals(FieldKey.DOWN, fieldKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN))
    }

    @Test fun theirReleaseIsSwallowedSoTheTextDoesNotMove() {
        assertEquals(FieldKey.SWALLOW, fieldKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_UP))
        assertEquals(FieldKey.SWALLOW, fieldKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP))
    }

    @Test fun backLetsGoOfTheBox() {
        assertEquals(FieldKey.BACK, fieldKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN))
        assertEquals(FieldKey.BACK, fieldKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP))
    }

    @Test fun leftRightOkAndTypingStillEdit() {
        for (k in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DEL))
            assertEquals(FieldKey.EDIT, fieldKey(k, KeyEvent.ACTION_DOWN))
    }
}
