// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.scan

/** LOW-1/LOW-2: how much KeepClear may write, from the device's free space. */
enum class SpaceMode {
    NORMAL,

    /** < 500 MB raw free: no thumbnail disk cache, capped index writes, banner. */
    LIGHT,

    /** < 100 MB raw free: in-memory scan only, MOVE start blocked, space-freeing actions first. */
    MINIMAL,
    ;

    val persistScanResults: Boolean get() = this != MINIMAL
    val diskThumbnails: Boolean get() = this == NORMAL
    val canStartMove: Boolean get() = this != MINIMAL

    companion object {
        const val LIGHT_BELOW = 500L * 1000 * 1000
        const val MINIMAL_BELOW = 100L * 1000 * 1000

        fun of(rawFreeBytes: Long): SpaceMode = when {
            rawFreeBytes < MINIMAL_BELOW -> MINIMAL
            rawFreeBytes < LIGHT_BELOW -> LIGHT
            else -> NORMAL
        }
    }
}
