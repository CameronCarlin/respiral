package app.respiral.security

import app.respiral.core.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface VaultSession {
    /** Monotonic state changes let private-route guards react immediately to lock events. */
    val changes: Flow<Long>
        get() = kotlinx.coroutines.flow.flowOf(0L)

    fun unlock(now: Instant)

    fun touch(now: Instant)

    fun lock()

    fun isUnlocked(now: Instant): Boolean
}

/** An in-memory vault session that expires after five minutes without activity. */
class DefaultVaultSession(
    private val clock: Clock,
) : VaultSession {
    private var lastActivity: Instant? = null
    private val changeVersion = MutableStateFlow(0L)

    override val changes: Flow<Long> = changeVersion

    override fun unlock(now: Instant) {
        lastActivity = now
        changeVersion.value += 1
    }

    override fun touch(now: Instant) {
        if (isUnlocked(now)) {
            lastActivity = now
            changeVersion.value += 1
        }
    }

    override fun lock() {
        lastActivity = null
        changeVersion.value += 1
    }

    override fun isUnlocked(now: Instant): Boolean {
        val activity = lastActivity ?: return false
        val inactiveFor = Duration.between(activity, now)
        return !inactiveFor.isNegative && inactiveFor < SESSION_TIMEOUT
    }

    fun unlock() = unlock(clock.now())

    fun touch() = touch(clock.now())

    fun isUnlocked(): Boolean = isUnlocked(clock.now())

    private companion object {
        val SESSION_TIMEOUT: Duration = Duration.ofMinutes(5)
    }
}
