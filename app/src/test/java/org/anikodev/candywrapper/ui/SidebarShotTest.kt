package org.anikodev.candywrapper.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class SidebarShotTest {
    private fun sidebar(dark: Boolean) = shot("sidebar-phone", dark) {
        SidebarPanel(
            current = Fixtures.environments[0], environments = Fixtures.environments, model = Fixtures.model,
            activeThreadId = "t1", now = Fixtures.NOW, actions = SidebarActions(),
        )
    }

    @Test fun sidebarLight() = sidebar(false)
    @Test fun sidebarDark() = sidebar(true)
}
