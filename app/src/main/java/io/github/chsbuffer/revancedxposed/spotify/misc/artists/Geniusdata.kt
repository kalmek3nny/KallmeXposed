package io.github.chsbuffer.revancedxposed.spotify.misc.artists

data class GeniusData(
    val songId: String,
    val url: String,
    val aboutText: String,
    val releaseDate: String,
    val producers: String,
    val annotations: List<GeniusAnnotation>
)

data class GeniusAnnotation(
    val fragment: String, // The actual lyric line
    val explanation: String // The meaning behind it
)