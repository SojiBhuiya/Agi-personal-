package com.agi.assistant.core.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * App-scoped state holder (ViewModel-like, but lifecycle independent so it
 * survives Activity recreation without AndroidX). Observers are notified on
 * the scope's dispatcher (the app passes a main-thread scope).
 *
 * Phase 1: only checks. Download/install will extend [UpdateState] later.
 */
class UpdateManager(
    private val repository: UpdateRepository,
    private val scope: CoroutineScope,
    /** Minimum interval between automatic checks (default 6 h). */
    private val minAutoCheckIntervalMs: Long = 6 * 60 * 60 * 1000L,
) {
    fun interface Observer { fun onStateChanged(state: UpdateState) }

    @Volatile var state: UpdateState = UpdateState.Idle
        private set
    private val observers = CopyOnWriteArraySet<Observer>()
    private var job: Job? = null

    fun addObserver(o: Observer) { observers += o; o.onStateChanged(state) }
    fun removeObserver(o: Observer) { observers -= o }

    /** Runs a check unless one is already in flight. */
    fun checkNow(): Job {
        job?.takeIf { it.isActive }?.let { return it }
        setState(UpdateState.Checking)
        return scope.launch {
            val result = repository.checkForUpdate()
            setState(repository.toState(result))
        }.also { job = it }
    }

    /** Called from app start; throttled so we do not hammer GitHub. */
    fun checkIfStale(): Job? {
        val stale = System.currentTimeMillis() - repository.lastCheckedAt > minAutoCheckIntervalMs
        return if (stale && state !is UpdateState.Checking) checkNow() else null
    }

    private fun setState(s: UpdateState) {
        state = s
        observers.forEach { it.onStateChanged(s) }
    }
}
