package app.respiral

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RespiralApplication::class)
class RespiralApplicationTest {
    @Test
    fun settings_repository_is_process_wide_and_reused() {
        val application = ApplicationProvider.getApplicationContext<RespiralApplication>()

        val first = RespiralApplication.from(application).settingsRepository
        val second = RespiralApplication.from(application.applicationContext).settingsRepository

        assertThat(first).isSameInstanceAs(second)
    }

    @Test
    fun vault_repository_is_process_wide_and_is_the_ready_repository() = runTest {
        val application = ApplicationProvider.getApplicationContext<RespiralApplication>()

        val direct = RespiralApplication.from(application).vaultRepository
        val awaited = RespiralApplication.from(application.applicationContext).awaitVaultRepository()

        assertThat(awaited).isSameInstanceAs(direct)
    }
}
