// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

/**
 * Material Symbols Rounded (Apache-2.0, THIRD_PARTY.md), only those used (design-system.md §5). [Back], [Chevron] and
 * [Undo] are auto-mirrored in right-to-left layouts.
 */
object KcIcons {
    val Safe: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_eco)
    val Review: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_photo_library)
    val Move: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_inventory_2)
    val Trash: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_auto_delete)
    val Verified: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_task_alt)
    val Settings: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_settings)
    val Apps: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_apps)
    val Back: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_arrow_back)
    val Chevron: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_chevron_right)
    val Undo: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_undo)
    val Selected: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_check_circle)
    val Info: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_info)
    val Schedule: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_schedule)
    val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_close)
    val ExpandMore: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.kc_ic_expand_more)

    /** The lane's icon (SAFE leaf, REVIEW photos, MOVE box). */
    @Composable
    fun lane(lane: KcLane): ImageVector = when (lane) {
        KcLane.SAFE -> Safe
        KcLane.REVIEW -> Review
        KcLane.MOVE -> Move
    }
}
