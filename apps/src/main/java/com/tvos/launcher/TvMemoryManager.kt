package com.tvos.launcher

import android.content.ComponentCallbacks2
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * Pure Kotlin / zero-dependency RAM, RecyclerView, and 60 FPS focus animation
 * manager tuned specifically for 2 GB RAM Android TV devices.
 */
object TvMemoryManager {

    private val focusInterpolator = DecelerateInterpolator(1.8f)

    /**
     * Handles Android TV memory trim callbacks gracefully:
     * - When the user opens YouTube / Prime Video (UI hidden), we keep the top dock
     *   icons warm (~1.6 MB) and release the rest so pressing Home feels instant.
     * - Under heavy background/critical pressure, we clear all cached icon bitmaps
     *   so Android's Low Memory Killer keeps the launcher process alive.
     */
    fun handleTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                TvIconComposer.clearCache()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // Keep ~8 dock icons hot in RAM and evict off-screen grid cards
                TvIconComposer.trimCache(1600)
            }
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                TvIconComposer.clearCache()
            }
        }
    }

    /**
     * Tunes a RecyclerView for smooth D-pad scrolling with minimal view allocations
     * on 2 GB RAM TV chipsets.
     */
    fun optimizeRecyclerView(
        recyclerView: RecyclerView,
        gridViewType: Int = 2,
        maxRecycledCards: Int = 12,
        viewCacheSize: Int = 4
    ) {
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(viewCacheSize)
        recyclerView.recycledViewPool.setMaxRecycledViews(gridViewType, maxRecycledCards)

        // Prevent double-view crossfade allocations during icon refreshes
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    /**
     * 60 FPS GPU RenderThread focus animation:
     * - Immediately cancels stale animations during fast D-pad taps
     * - Animates only GPU properties (scaleX, scaleY, translationZ) with zero relayout
     */
    fun animateTvFocus(
        view: View,
        hasFocus: Boolean,
        focusedScale: Float = 1.10f,
        focusedTranslationZ: Float = 0f,
        durationMs: Long = 125L
    ) {
        view.animate().cancel()
        val anim = view.animate()
            .scaleX(if (hasFocus) focusedScale else 1.0f)
            .scaleY(if (hasFocus) focusedScale else 1.0f)
            .setDuration(durationMs)
            .setInterpolator(focusInterpolator)

        if (focusedTranslationZ > 0f) {
            anim.translationZ(if (hasFocus) focusedTranslationZ else 0f)
        }
        anim.start()
    }

    /**
     * Smooth, flicker-free alpha transition that cancels in-flight animations first.
     */
    fun animateAlpha(
        view: View,
        targetAlpha: Float,
        durationMs: Long = 110L,
        endAction: (() -> Unit)? = null
    ) {
        view.animate().cancel()
        val anim = view.animate()
            .alpha(targetAlpha)
            .setDuration(durationMs)
            .setInterpolator(focusInterpolator)
        if (endAction != null) {
            anim.withEndAction(endAction)
        }
        anim.start()
    }
}