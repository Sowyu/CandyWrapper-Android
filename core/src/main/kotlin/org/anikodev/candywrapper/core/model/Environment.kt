package org.anikodev.candywrapper.core.model

import kotlinx.serialization.Serializable

enum class EnvironmentKind { PAIRED, CONNECT }

/** An address the host reported in `ServerConfig.directEndpoints`: `lan` or `tailnet`. */
@Serializable
data class DirectRoute(val kind: String, val httpBaseUrl: String)

/**
 * A saved environment. For [EnvironmentKind.PAIRED] the bases are the pairing address; for
 * [EnvironmentKind.CONNECT] they are the tunnel endpoint the relay returned last.
 */
data class SavedEnvironment(
    val id: String,
    val label: String,
    val kind: EnvironmentKind,
    val httpBase: String,
    val wsBase: String,
    val directRoutes: List<DirectRoute> = emptyList(),
    val addedAtMs: Long = 0,
)

/**
 * One project as the sidebars show and group it (contracts `OrchestrationProjectShell`). The
 * repository fields come from `repositoryIdentity` and decide which projects on different
 * computers are the same project (client-runtime `projectGrouping.ts`).
 */
@Serializable
data class ProjectSummary(
    val id: String,
    val title: String,
    val workspaceRoot: String,
    val envId: String = "",
    /** `repositoryGroupingKeyOf`: the origin's canonical key, else the identity's. */
    val repoKey: String? = null,
    /** `repositoryGroupingDisplayNameOf`. */
    val repoName: String? = null,
    /** `repositoryIdentity.name`. */
    val repoShortName: String? = null,
    /** `updatedAt`, else `createdAt`, in epoch ms. */
    val updatedAt: Long = 0,
) {
    /** `env:project`, unique across computers. */
    val key: String get() = "$envId:$id"
}
