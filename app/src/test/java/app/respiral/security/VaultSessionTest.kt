package app.respiral.security

import app.respiral.core.time.Clock
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VaultSessionTest {
    private val clock = FixedClock()
    private val session = DefaultVaultSession(clock)

    @Test
    fun session_expires_after_five_minutes_of_inactivity() {
        session.unlock(instant("2026-08-26T08:00:00Z"))

        assertThat(session.isUnlocked(instant("2026-08-26T08:04:59Z"))).isTrue()
        assertThat(session.isUnlocked(instant("2026-08-26T08:05:00Z"))).isFalse()
    }

    @Test
    fun touch_extends_an_active_session_from_the_most_recent_activity() {
        session.unlock(instant("2026-08-26T08:00:00Z"))
        session.touch(instant("2026-08-26T08:04:00Z"))

        assertThat(session.isUnlocked(instant("2026-08-26T08:08:59Z"))).isTrue()
        assertThat(session.isUnlocked(instant("2026-08-26T08:09:00Z"))).isFalse()
    }

    @Test
    fun lock_immediately_invalidates_an_active_session() {
        val now = instant("2026-08-26T08:00:00Z")
        session.unlock(now)

        session.lock()

        assertThat(session.isUnlocked(now)).isFalse()
    }

    @Test
    fun touch_cannot_restore_a_session_that_was_explicitly_locked() {
        val now = instant("2026-08-26T08:00:00Z")
        session.unlock(now)
        session.lock()

        session.touch(now)

        assertThat(session.isUnlocked(now)).isFalse()
    }

    @Test
    fun clock_convenience_methods_expire_the_session_after_five_minutes() {
        clock.current = instant("2026-08-26T08:00:00Z")
        session.unlock()
        clock.current = instant("2026-08-26T08:05:00Z")

        assertThat(session.isUnlocked()).isFalse()
    }

    @Test
    fun session_changes_are_observable_when_it_is_unlocked_or_locked() = runTest {
        val initial = session.changes.first()

        session.unlock(instant("2026-08-26T08:00:00Z"))
        assertThat(session.changes.first()).isEqualTo(initial + 1)

        session.lock()
        assertThat(session.changes.first()).isEqualTo(initial + 2)
    }

    private fun instant(value: String): Instant = Instant.parse(value)

    private class FixedClock : Clock {
        var current: Instant = Instant.parse("2026-08-26T08:00:00Z")

        override fun now(): Instant = current
    }
}
