package com.agi.assistant

import com.agi.assistant.core.update.*
import com.agi.assistant.core.update.AutoCheckPolicy.Decision
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** Phase 5: automatic update checking – cooldown, throttle, offline silence, re-prompt suppression. */
object AutoCheckTest {
    private var fails = 0; private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") {
        if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name  -> $detail") }
    }
    class MemPrefs : UpdatePreferences { override var postponedTag: String? = null; override var postponedAt: Long = 0; override var lastCheckedAt: Long = 0; override var lastSeenTag: String? = null; override var lastPromptedTag: String? = null; override var lastPromptedAt: Long = 0 }
    private fun info(v: String = "0.2.0", mandatory: Boolean = false) =
        UpdateInfo(v, 2, "v$v", "AGI $v", "", "https://github.com/x/y/releases/download/v$v/agi.apk", "agi.apk", -1, "", "https://github.com/x/y", true, isMandatory = mandatory)
    private const val H = 3600_000L

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("AutoCheckPolicy decisions:")
        var clock = 1_000_000_000L
        val prefs = MemPrefs()
        val p = AutoCheckPolicy(prefs, cooldownMs = 6 * H, minGapMs = 60_000, now = { clock })
        check("offline -> OFFLINE, never checks", p.decide(false, UpdateState.Idle) == Decision.OFFLINE)
        check("first start online -> CHECK", p.decide(true, UpdateState.Idle) == Decision.CHECK)
        p.markAttempt()
        check("immediately again (activity recreation) -> THROTTLED", p.decide(true, UpdateState.Idle) == Decision.THROTTLED)
        clock += 61_000
        check("after in-process gap but before result recorded -> CHECK (no persisted time yet)", p.decide(true, UpdateState.Idle) == Decision.CHECK)
        p.recordResult(UpdateCheckResult.Success(info()))
        check("result persists lastCheckedAt and lastSeenTag", prefs.lastCheckedAt == clock && prefs.lastSeenTag == "v0.2.0")
        clock += 2 * H
        check("2 h later -> COOLDOWN (6 h)", p.decide(true, UpdateState.Idle) == Decision.COOLDOWN)
        clock += 5 * H
        check("7 h later -> CHECK", p.decide(true, UpdateState.Idle) == Decision.CHECK)
        check("busy while checking", p.decide(true, UpdateState.Checking) == Decision.BUSY)
        check("busy while downloading", p.decide(true, UpdateState.Downloading(info(), 1, 2)) == Decision.BUSY)
        check("busy while installer open", p.decide(true, UpdateState.InstallerLaunched(info(), java.io.File("x"))) == Decision.BUSY)
        check("cooldown survives restart (new policy over same prefs)", AutoCheckPolicy(MemPrefs().also { it.lastCheckedAt = clock - H }, now = { clock }).decide(true, UpdateState.Idle) == Decision.COOLDOWN)
        check("failure result still records time (no hammering after errors)", run { val m = MemPrefs(); AutoCheckPolicy(m, now = { clock }).recordResult(UpdateCheckResult.Failure(UpdateError.NETWORK, "x")); m.lastCheckedAt == clock && m.lastSeenTag == null })
        check("isNewSighting", p.isNewSighting(info("0.3.0")) && !p.isNewSighting(info("0.2.0")))

        println("Prompt policy across restarts:")
        run {
            val pr = MemPrefs(); var t = 5_000_000L
            val pol1 = UpdatePromptPolicy(pr, now = { t })
            check("first sighting prompts", pol1.shouldPrompt(info()))
            pol1.markShown(info())
            check("persisted lastPromptedTag", pr.lastPromptedTag == "v0.2.0" && pr.lastPromptedAt == t)
            val pol2 = UpdatePromptPolicy(pr, now = { t + H }) // app restarted 1 h later
            check("same release after restart within 24 h -> no automatic re-prompt", !pol2.shouldPrompt(info()))
            check("a newer release still prompts", pol2.shouldPrompt(info("0.3.0")))
            check("mandatory release still prompts", pol2.shouldPrompt(info(mandatory = true)))
            pol2.resetSession()
            check("manual check bypasses the re-prompt limit", pol2.shouldPrompt(info()))
            pol2.markShown(info())
            check("after showing, the bypass is consumed", !UpdatePromptPolicy(pr, now = { t + 2 * H }).shouldPrompt(info()))
            check("after 25 h -> prompts again", UpdatePromptPolicy(pr, now = { t + 25 * H }).shouldPrompt(info()))
        }

        println("Manager automatic checks:")
        run {
            val calls = AtomicInteger(); var online = true; var fail = false
            val i = info()
            val checker = object : UpdateChecker { override suspend fun check(installedVersionName: String, installedVersionCode: Long): UpdateCheckResult {
                calls.incrementAndGet(); return if (fail) UpdateCheckResult.Failure(UpdateError.NETWORK, "offline") else UpdateCheckResult.Success(i) } }
            val repo = UpdateRepository(checker, { InstalledVersion("0.1.0", 1) }, Dispatchers.Unconfined)
            val pr = MemPrefs(); var t = 900_000_000L
            val scope = CoroutineScope(Dispatchers.Default)
            val mgr = UpdateManager(repo, scope, autoPolicy = AutoCheckPolicy(pr, now = { t }))
            val states = java.util.Collections.synchronizedList(ArrayList<UpdateState>())
            mgr.addObserver { states += it }

            check("offline: no request, state untouched, decision OFFLINE", mgr.checkAutomatically(false) == null && calls.get() == 0 && mgr.state is UpdateState.Idle && mgr.lastAutoDecision == Decision.OFFLINE)
            mgr.checkAutomatically(true)!!.join()
            check("online: one request -> UpdateAvailable", calls.get() == 1 && mgr.state is UpdateState.UpdateAvailable, mgr.state)
            repeat(5) { mgr.checkAutomatically(true) }
            check("five rapid onStart calls -> no extra requests", calls.get() == 1 && mgr.lastAutoDecision == Decision.THROTTLED)
            t += 2 * H
            check("2 h later -> cooldown, no request", mgr.checkAutomatically(true) == null && calls.get() == 1)
            mgr.checkNow().join()
            check("manual check always allowed (bypasses cooldown)", calls.get() == 2)
            t += 7 * H; fail = true
            mgr.checkAutomatically(true)!!.join()
            check("automatic check failing (network) -> request made but previous state kept, no Error shown", calls.get() == 3 && mgr.state is UpdateState.UpdateAvailable && states.none { it is UpdateState.Error }, mgr.state)
            mgr.checkNow().join()
            check("manual check failing -> Error IS shown (user asked)", mgr.state is UpdateState.Error, mgr.state)
            t += 7 * H
            mgr.checkAutomatically(true)!!.join()
            check("automatic failure from Error state stays Error (nothing new to hide)", mgr.state is UpdateState.Error)
            check("Idle → automatic failure → back to Idle (not Checking forever)", run {
                val m2 = UpdateManager(repo, scope, autoPolicy = AutoCheckPolicy(MemPrefs(), now = { t })); m2.checkAutomatically(true)!!.join(); m2.state is UpdateState.Idle })
            scope.cancel()
        }
        println("\n$passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
