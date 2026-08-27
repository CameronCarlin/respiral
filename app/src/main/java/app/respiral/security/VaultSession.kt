package app.respiral.security

import app.respiral.core.time.Clock
import java.time.Duration
import java.time.Instant

interface VaultSession {
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

    override fun unlock(now: Instant) {
        lastActivity = now
    }

    override fun touch(now: Instant) {
        if (isUnlocked(now)) lastActivity = now
    }

    override fun lock() {
        lastActivity = null
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
