package com.fairylive.app.core.compositor

import com.fairylive.app.core.compositor.model.OverlayElement

/**
 * The overlay scene: an ordered, z-sorted collection of [OverlayLayer]s that the
 * renderer draws on top of the capture texture. Kept deliberately simple — the
 * animation/time-based system can later attach per-layer property curves here
 * without changing the model.
 */
class OverlayScene {

    private val layers = mutableListOf<OverlayLayer>()

    /** All layers, z-ascending (bottom → top). */
    val orderedLayers: List<OverlayLayer>
        get() = synchronized(lock) {
            layers.sortedBy { it.transform.zIndex }
        }

    /** Alias used by tests/UI: same as [orderedLayers]. */
    val sortedLayers: List<OverlayLayer>
        get() = orderedLayers

    fun find(id: String): OverlayLayer? = get(id)

    fun setVisible(id: String, visible: Boolean): Boolean {
        return synchronized(lock) {
            val idx = layers.indexOfFirst { it.element.id == id }
            if (idx >= 0) {
                val cur = layers[idx]
                layers[idx] = OverlayLayer(cur.element.copy(visible = visible))
                true
            } else {
                false
            }
        }
    }

    val size: Int
        get() = synchronized(lock) { layers.size }

    val isEmpty: Boolean
        get() = synchronized(lock) { layers.isEmpty() }

    fun add(element: OverlayElement): OverlayLayer {
        val layer = OverlayLayer(element)
        synchronized(lock) {
            val idx = layers.indexOfFirst { it.element.id == element.id }
            if (idx >= 0) {
                layers[idx] = layer
            } else {
                if (layers.size >= com.fairylive.app.core.compositor.limits.CompositorLimits.MAX_ACTIVE_OVERLAYS) {
                    val lowest = layers.minByOrNull { it.transform.zIndex }
                    if (lowest != null) layers.remove(lowest)
                }
                layers.add(layer)
            }
        }
        return layer
    }

    fun remove(id: String): OverlayLayer? {
        return synchronized(lock) {
            val idx = layers.indexOfFirst { it.element.id == id }
            if (idx >= 0) layers.removeAt(idx) else null
        }
    }

    fun get(id: String): OverlayLayer? = synchronized(lock) {
        layers.firstOrNull { it.element.id == id }
    }

    fun clear() {
        synchronized(lock) {
            layers.clear()
        }
    }

    fun updateElement(id: String, newElement: OverlayElement): Boolean {
        return synchronized(lock) {
            val idx = layers.indexOfFirst { it.element.id == id }
            if (idx >= 0) {
                layers[idx] = OverlayLayer(newElement)
                true
            } else {
                false
            }
        }
    }

    /** Replace every layer with the given elements (scene push). */
    fun replaceAll(elements: List<OverlayElement>) {
        synchronized(lock) {
            layers.clear()
            elements.forEach { layers.add(OverlayLayer(it)) }
        }
    }

    private val lock = Any()
}