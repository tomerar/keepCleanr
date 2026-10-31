// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/** ONB-2/ONB-4: what KeepClear can see in shared storage right now. */
enum class MediaAccess {
    NONE,

    /** Android 14+ "Selected photos": results must be labelled partial (ONB-4). */
    PARTIAL,
    FULL,
    ;

    companion object {
        /** Permissions to request for media access on this API level. */
        fun requestPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )

            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )

            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun current(context: Context): MediaAccess {
            fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            return when {
                Build.VERSION.SDK_INT >= 33 -> {
                    when {
                        granted(
                            Manifest.permission.READ_MEDIA_IMAGES,
                        ) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> FULL

                        Build.VERSION.SDK_INT >= 34 && granted(
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                        ) -> PARTIAL

                        granted(
                            Manifest.permission.READ_MEDIA_IMAGES,
                        ) || granted(Manifest.permission.READ_MEDIA_VIDEO) -> PARTIAL

                        else -> NONE
                    }
                }

                else -> {
                    if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) FULL else NONE
                }
            }
        }

        /** All files access (foss flavor, ONB-5). */
        fun hasAllFiles(): Boolean = Environment.isExternalStorageManager()
    }
}
