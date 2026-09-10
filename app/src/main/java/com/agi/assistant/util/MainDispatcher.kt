package com.agi.assistant.util

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * A main-thread dispatcher that only depends on the Android framework.
 *
 * kotlinx-coroutines-android is not required; this keeps the dependency
 * surface minimal so the app can be compiled with the plain JVM coroutines
 * artifact (see scripts/build_apk.sh).
 */
object MainDispatcher : CoroutineDispatcher() {
    private val handler = Handler(Looper.getMainLooper())

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        Looper.myLooper() != Looper.getMainLooper()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }
}

/** Creates a supervisor scope bound to the main thread. */
fun mainScope(): CoroutineScope = CoroutineScope(SupervisorJob() + MainDispatcher)
