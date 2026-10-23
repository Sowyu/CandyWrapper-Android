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

@Serializable
data class ProjectSummary(val id: String, val title: String, val workspaceRoot: String)
