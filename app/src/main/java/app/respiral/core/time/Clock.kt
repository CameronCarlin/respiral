package app.respiral.core.time

import java.time.Instant

interface Clock {
    fun now(): Instant
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
