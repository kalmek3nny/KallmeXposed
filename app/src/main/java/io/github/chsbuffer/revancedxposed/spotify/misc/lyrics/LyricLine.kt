package io.github.chsbuffer.revancedxposed.spotify.misc.lyrics

import android.widget.TextView

data class LyricLine(
    val timeMs: Long,
    val text: String,
    // Use @Transient so if you ever serialize this, the View is ignored.
    @Transient var view: TextView? = null
) {
    // Helper to safely detach the view to prevent memory leaks during UI rebuilds
    fun detachView() {
        view = null
    }
}