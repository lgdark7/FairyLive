package com.fairylive.app.core.render

/**
 * RenderDomain explicitly categorizes visual components in FAIRY LIVE to enforce
 * absolute isolation between content broadcasted to external viewers (RTMP) and
 * content visible solely to the mobile creator/streamer (local UI).
 */
enum class RenderDomain {
    /**
     * Components rendered and composited into the broadcast video stream.
     * Enters GLES Compositor -> MediaCodec AVC -> RTMP.
     */
    BROADCAST,

    /**
     * Components rendered exclusively on the creator's local device screen.
     * MUST NEVER enter BroadcastScene, GLES Compositor, or RTMP packets.
     * Examples: Streamer Floating HUD, YouTube Live Chat, Diagnostics, Mic/Camera Dock.
     */
    CREATOR_ONLY
}
