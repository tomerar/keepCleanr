// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.gallery

/**
 * What the gallery may read right now (GAL-1, AC-G1, C211). Derived from live platform state on every check; the
 * last value is remembered only to notice a change, never as authority to read anything.
 */
enum class GalleryScope {
    /** No photo access: nothing is analysed (E-G01). */
    NONE,

    /** Android 14+ "Selected photos": only the chosen items are visible; results are always partial. */
    SELECTED,

    /** Every image in shared storage (READ_MEDIA_IMAGES, or READ_EXTERNAL_STORAGE before Android 13). */
    FULL_IMAGES,

    /** foss flavour's all-files access. */
    ALL_FILES,
    ;

    /** True when the scope itself covers the whole photo library (the run can still be partial for other reasons). */
    val complete: Boolean get() = this == FULL_IMAGES || this == ALL_FILES
}

/** The live scope and its permission epoch (GAL-12, E-G02). An epoch is never 0 and never goes back. */
data class GalleryAuthorization(val scope: GalleryScope, val epoch: Long) {
    init {
        require(epoch >= 1)
    }
}

/** C211: any scope change, narrower or wider, starts a new permission epoch; the same scope keeps its epoch. */
object PermissionEpoch {
    fun advance(last: GalleryAuthorization?, scope: GalleryScope): GalleryAuthorization = when {
        last == null -> GalleryAuthorization(scope, 1)
        last.scope == scope -> last
        else -> GalleryAuthorization(scope, Math.addExact(last.epoch, 1L))
    }
}

/** Live authorization port. Every call re-reads platform state; implementations persist only scope name + epoch. */
fun interface GalleryScopeSource {
    suspend fun current(): GalleryAuthorization
}
