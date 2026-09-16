package ca.mpreg.webgpuviewer.renderer

import android.os.Trace

/*
 * Sections for a system trace (Perfetto, Android GPU Inspector), all named "wgv:..." so they stand
 * out from the host app's own. Nothing is recorded unless a trace is running, and a section then
 * costs one check of whether the app tag is on.
 *
 * They time the CPU side only. A draw or a staged tile is recorded here and run by the GPU later,
 * so a short section can still be an expensive frame - the GPU's own time is on its queue track.
 */

/**
 * Runs [block] inside a trace section named [label].
 *
 * Never suspend inside [block]. A section closes whatever was opened last on its thread, and the
 * render thread is shared: a frame that suspended mid-section would let the tile worker open and
 * close its own in between, and each would end the other's.
 */
internal inline fun <T> traced(label: String, block: () -> T): T {
    Trace.beginSection(label)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
