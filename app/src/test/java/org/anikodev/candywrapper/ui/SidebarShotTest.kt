package org.anikodev.candywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class SidebarShotTest {
    /** The new sidebar on one machine: pinned and active cards, the Snoozed and Settled shelves closed. */
    private fun sidebar(dark: Boolean) = shot("sidebar-phone", dark) {
        SidebarPanel(Fixtures.environments[0], Fixtures.environments, Fixtures.data, "e1:t1", Fixtures.NOW, SidebarActions())
    }
    @Test fun sidebarLight() = sidebar(false)
    @Test fun sidebarDark() = sidebar(true)

    /** All machines: one list across both, t3code merged, each row naming its machine; the shelves open. */
    private fun allMachines(dark: Boolean) = shot("sidebar-all-machines-phone", dark) {
        SidebarPanel(Fixtures.all, Fixtures.environments, Fixtures.allData, "e1:t1", Fixtures.NOW, SidebarActions(), shelves = Shelves(snoozed = true, settled = true))
    }
    @Test fun allMachinesLight() = allMachines(false)
    @Test fun allMachinesDark() = allMachines(true)

    @Config(qualifiers = TABLET)
    @Test fun allMachinesTabletLight() = allMachinesTablet(false)
    @Config(qualifiers = TABLET)
    @Test fun allMachinesTabletDark() = allMachinesTablet(true)
    private fun allMachinesTablet(dark: Boolean) = shot("sidebar-all-machines-tablet", dark) {
        AppShell(
            DrawerState(open = false),
            sidebar = { SidebarPanel(Fixtures.all, Fixtures.environments, Fixtures.allData, "e1:t1", Fixtures.NOW, SidebarActions(), shelves = Shelves(snoozed = true)) },
        ) { ThreadShotTest.ThreadScreenFixture() }
    }

    /** Settings > Legacy sidebar on: the per-project tree, here across both machines. */
    private fun legacy(dark: Boolean) = shot("sidebar-legacy-phone", dark) {
        LegacySidebarPanel(Fixtures.all, Fixtures.environments, Fixtures.allModel, "e1:t1", Fixtures.NOW, SidebarActions())
    }
    @Test fun legacyLight() = legacy(false)
    @Test fun legacyDark() = legacy(true)

    /** The settings that bring back the legacy sidebar and plan mode, both off by default. */
    private fun settings(dark: Boolean) = shot("settings-legacy-phone", dark) {
        Column(Modifier.fillMaxSize().background(Candy.colors.background)) {
            TopBar("Settings", onBack = {})
            Column(Modifier.padding(horizontal = 16.dp)) {
                GroupLabel("Legacy features")
                LegacyFeatures(planMode = false, legacySidebar = false, onPlanMode = {}, onLegacySidebar = {})
                GroupLabel("Turned on")
                LegacyFeatures(planMode = true, legacySidebar = true, onPlanMode = {}, onLegacySidebar = {})
            }
        }
    }
    @Test fun settingsLight() = settings(false)
    @Test fun settingsDark() = settings(true)
}
