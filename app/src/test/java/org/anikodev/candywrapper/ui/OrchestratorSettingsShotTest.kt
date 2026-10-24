package org.anikodev.candywrapper.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import java.nio.file.Files
import org.anikodev.candywrapper.core.model.ModelInfo
import org.anikodev.candywrapper.core.model.OptionChoice
import org.anikodev.candywrapper.core.model.OptionDescriptor
import org.anikodev.candywrapper.core.model.OptionValue
import org.anikodev.candywrapper.core.model.ProviderInfo
import org.anikodev.candywrapper.core.model.ServerInfo
import org.anikodev.candywrapper.core.orchestrator.BotIdentity
import org.anikodev.candywrapper.core.orchestrator.BotMood
import org.anikodev.candywrapper.core.orchestrator.BotShape
import org.anikodev.candywrapper.core.orchestrator.OrchestratorConfig
import org.anikodev.candywrapper.core.orchestrator.OrchestratorModelRow
import org.anikodev.candywrapper.orchestrator.OrchestratorUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings > Orchestrator under Redesign: the page from the top, and the Look sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class OrchestratorSettingsShotTest {
    private val dir = Files.createTempDirectory("orchestrator").toFile()
    private val photo = redesignPhoto(dir)
    private val store = DitherArtStore(dir, dir, renderOnPeek = true)

    private fun effort(id: String) = OptionDescriptor(
        id, "Effort", "select", listOf(OptionChoice("low", "Low", false), OptionChoice("medium", "Medium", true), OptionChoice("high", "High", false)),
    )

    private val server = ServerInfo(
        providers = listOf(
            ProviderInfo("claudeAgent", "Claude", "claudeAgent", true, listOf(ModelInfo("claude-opus-5-5", "Claude Opus 5.5", listOf(effort("effort")))), true, emptyList()),
            ProviderInfo("codex", "Codex", "codex", true, listOf(ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", listOf(effort("reasoningEffort")))), true, emptyList()),
        ),
    )

    /** The missing row first, so its warning shows above the fold. */
    private val rows = listOf(
        OrchestratorModelRow("long", "Long reads", "claudeAgent", "claude-sonnet-4", listOf(OptionValue("effort", "low")), "Reading a big codebase end to end."),
    ) + OrchestratorModelRow.defaults.take(3)

    /** What the controller's `availableRows` keeps: the rows whose model the catalog lists. */
    private val offered = rows.filter { r -> server.provider(r.instanceId)?.model(r.model) != null }.map { it.id }.toSet()

    @Composable
    private fun panel(config: OrchestratorConfig, openLook: Boolean = false) {
        OrchestratorSettingsPanel(
            config = config,
            ui = OrchestratorUi(active = true, name = config.displayName, envId = "e1", machine = "anika-mbp", connected = true, threadId = "t9"),
            machines = listOf(OrchestratorMachineChoice("e1", "anika-mbp", true), OrchestratorMachineChoice("e2", "mini", false)),
            machineId = "e1",
            projects = Fixtures.projects.filter { it.envId == "e1" },
            server = server,
            offered = offered,
            onUpdate = {},
            onStart = {},
            onBack = {},
            openLook = openLook,
        )
    }

    private fun shot(name: String, dark: Boolean, identity: BotIdentity, content: @Composable () -> Unit) {
        wallClock = { Fixtures.NOW }
        warmBotPoses(identity, BotMood.IDLE, 40f * 3)
        warmBotPoses(identity, BotMood.IDLE, 24f * 3)
        warmBotPoses(identity, BotMood.IDLE, 96f * 3)
        for (shape in BotShape.entries) warmBotPoses(BotIdentity.of(shape, identity.color), BotMood.IDLE, 28f * 3)
        captureRoboImage(
            "src/test/snapshots/redesign/$name.${if (dark) "dark" else "light"}.png",
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)),
        ) {
            CandyTheme(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT, RedesignStyle(on = true, picture = photo)) {
                CompositionLocalProvider(LocalProjectIcons provides Fixtures.icons, LocalArtStore provides store) {
                    AppShell(DrawerState(open = false), sidebar = {}) { ArtEmphasisEffect(ArtEmphasis.SETTINGS); content() }
                }
            }
        }
    }

    private fun settings(dark: Boolean) {
        val config = OrchestratorConfig(enabled = true, models = rows)
        shot("orchestrator-settings", dark, BotIdentity.ORCHESTRATOR) { panel(config) }
    }
    @Test fun settingsLight() = settings(false)
    @Test fun settingsDark() = settings(true)

    private fun look(dark: Boolean) {
        val config = OrchestratorConfig(enabled = true, models = rows, botShape = BotShape.CLOUD.ordinal, botColor = 7)
        shot("orchestrator-look", dark, BotIdentity.fromRaw(config.botShape, config.botColor)) { panel(config, openLook = true) }
    }
    @Test fun lookLight() = look(false)
    @Test fun lookDark() = look(true)
}
