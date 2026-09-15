package ca.mpreg.webgpuviewer.renderer

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import android.view.View

/**
 * What this device can afford to spend on the tile cache.
 *
 * The cache was held to a flat 16-64 MB of tiles, 1.5 screens' worth, whatever it was running on.
 * Of those the byte range is the part that almost never binds: a [TileRenderer.TILE_SIZE] tile is
 * 256 KB, so 1440x3200 asks for about 180 of them and the 64 MB ceiling allows 256. Raising that
 * ceiling alone would change nothing on any phone. What decides the size of the cache is how many
 * screens of tiles it keeps, and a device with memory to spare can afford to hold more of the
 * strip it has just scrolled past instead of generating it again on the way back.
 *
 * Attached like [Thermals] and [Hdr]'s colour-mode host: a view registers on attach, so the
 * library never holds a [Context] of its own. Until one does - and on a device that will not
 * answer - every figure here is the fixed one the renderer used before, so nothing depends on
 * this having run.
 */
object DeviceMemory {

    private const val TAG = "DeviceMemory"

    private const val MB = 1024 * 1024
    private const val GB = 1024L * 1024 * 1024

    private class Budget(
        val screens: Float,
        val floorBytes: Int,
        val ceilingBytes: Int,
        val animatedBytes: Int,
    )

    /** What the renderer used at every size before this existed, and the fallback throughout. */
    private val DEFAULT = Budget(
        screens = 1.5f,
        floorBytes = 16 * MB,
        ceilingBytes = 64 * MB,
        animatedBytes = 64 * MB,
    )

    /**
     * Total physical RAM to tile budget.
     *
     * Deliberately coarse. What matters is how much of the page around the viewport stays
     * resident, and a step every few gigabytes is as fine a distinction as that supports -
     * anything smoother would be invented precision. The floor comes down as well as the ceiling
     * going up: 16 MB of tiles is a real imposition on a 2 GB device showing a small viewport,
     * which is exactly where a fixed floor was worst.
     */
    private fun budgetFor(totalBytes: Long, lowRam: Boolean): Budget = when {
        lowRam || totalBytes < 3 * GB -> Budget(1f, 8 * MB, 32 * MB, 32 * MB)
        totalBytes < 6 * GB -> DEFAULT
        totalBytes < 10 * GB -> Budget(2f, 16 * MB, 96 * MB, 128 * MB)
        else -> Budget(2.5f, 16 * MB, 128 * MB, 192 * MB)
    }

    @Volatile
    private var budget: Budget = DEFAULT

    /**
     * Register [view]'s device as the one to size against, or null to go back to the default.
     * Safe to call repeatedly; a second attach simply re-reads.
     */
    fun attachHost(view: View?) {
        if (view == null) {
            budget = DEFAULT
            return
        }
        budget = try {
            val manager = view.context.applicationContext
                .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            budgetFor(info.totalMem, manager.isLowRamDevice)
        } catch (e: Exception) {
            Log.w(TAG, "could not read device memory, keeping the default tile budget", e)
            DEFAULT
        }
    }

    /** Screens' worth of tiles the cache may hold around the viewport. */
    val cacheScreens: Float get() = budget.screens

    /** The low end of the byte range that many screens of tiles is held to. */
    val cacheFloorBytes: Int get() = budget.floorBytes

    /** The high end of it - see [TileRenderer.budgetTiles]. */
    val cacheCeilingBytes: Int get() = budget.ceilingBytes

    /**
     * What one animated page may hold in decoded frames.
     *
     * Every frame of an animated page is a full-resolution texture - they are built without
     * mipmaps, so there is no smaller level to fall back on - and all of them stay resident for
     * as long as the page is cached. A 1000x1500 page at sixty frames is a third of a gigabyte
     * of GPU memory for one page, which is a crash rather than a slow reader.
     *
     * Generous on purpose: ordinary animated content is nowhere near it, so the cap costs normal
     * reading nothing and only bites on the page that would otherwise take the app down.
     */
    val animatedPageBytes: Int get() = budget.animatedBytes
}
