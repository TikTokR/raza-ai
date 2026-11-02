package com.raza.ai.voice

import android.os.Handler
import android.os.Looper
import com.raza.ai.core.WakeState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process meeting point between the foreground wake service and the UI.
 * The service publishes its state; the UI registers a handler while it is visible.
 */
object WakeHub {
    /** Implemented by the service so the UI can borrow the microphone without two recognisers clashing. */
    interface Control {
        /** Stop wake listening so the app can use the mic. Returns true if it was actively listening. */
        fun pauseForCommand(): Boolean

        /** Wake listening may continue (no-op if the user paused it on purpose). */
        fun resumeAfterCommand()
    }

    @Volatile
    var state: WakeState = WakeState.OFF
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var control: Control? = null

    /** Set while the UI is visible. Returns true if the UI took over handling the wake event. */
    @Volatile
    var wakeHandler: ((command: String?) -> Boolean)? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun publish(newState: WakeState, newError: String?) {
        state = newState
        error = newError
        main.post { listeners.forEach { it() } }
    }
}
