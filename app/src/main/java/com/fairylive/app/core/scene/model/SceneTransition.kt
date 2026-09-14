package com.fairylive.app.core.scene.model

enum class SceneTransitionType {
    /** Instantaneous cut on the next compositor frame (0ms). */
    CUT,

    /** Linear opacity crossfade between outgoing and incoming scene elements. */
    CROSSFADE
}

/**
 * Declares how a scene transition should be animated by the compositor.
 */
data class SceneTransition(
    val type: SceneTransitionType = SceneTransitionType.CUT,
    val durationMs: Long = 0L
) {
    companion object {
        val CUT = SceneTransition(SceneTransitionType.CUT, 0L)
        val CROSSFADE = SceneTransition(SceneTransitionType.CROSSFADE, 300L)
    }
}
