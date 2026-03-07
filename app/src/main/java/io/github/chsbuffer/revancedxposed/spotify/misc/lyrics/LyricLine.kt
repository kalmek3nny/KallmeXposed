package io.github.chsbuffer.revancedxposed.spotify.misc.lyrics

import android.widget.TextView

data class LyricLine(
    val timeMs: Long,
    val text: String,
    var view: TextView? = null
)