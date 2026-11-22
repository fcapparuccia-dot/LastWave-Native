package com.lastwave.app.playback

/** Suffix every artist radio appends to its queue label. */
private const val ARTIST_RADIO_SUFFIX = " Radio"

/**
 * Queue label of an artist radio. Every entry point (car action, artist page,
 * car library and search rows) builds it here: the car's radio button keys
 * its "playing" state off this label, so a hand-written label would leave the
 * button dim while the radio runs.
 */
internal fun artistRadioLabel(artist: String): String = "${artist.trim()}$ARTIST_RADIO_SUFFIX"

/**
 * True while the queue was started by an artist radio. Comparing the seeded
 * artist with [MusicPlayerState.current] instead would drop the highlight as
 * soon as the radio moved on to a different artist.
 */
internal val MusicPlayerState.isArtistRadioQueue: Boolean
    get() = sourceLabel.endsWith(ARTIST_RADIO_SUFFIX)
