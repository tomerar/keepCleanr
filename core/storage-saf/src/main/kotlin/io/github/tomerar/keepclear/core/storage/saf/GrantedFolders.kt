// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.saf

import io.github.tomerar.keepclear.core.storage.KPath

/**
 * M12b, ONB-6: which granted folders KeepClear mounts, and where. Only folders of Android's own storage provider
 * have a real path (its document ids are `<volume>:<path>`); only WhatsApp's folders are accepted for now.
 */
object GrantedFolders {
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** Relative to the volume; the picker opens at the first one (ONB-6 `EXTRA_INITIAL_URI`). */
    val WHATSAPP = listOf(
        "Android/media/com.whatsapp/WhatsApp",
        "Android/media/com.whatsapp.w4b/WhatsApp Business",
    )

    private val sdCard = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")

    /** `primary:Android/media/com.whatsapp/WhatsApp` → `/storage/emulated/0/Android/media/com.whatsapp/WhatsApp`. */
    fun mountPoint(authority: String, treeDocumentId: String): String? {
        if (authority != EXTERNAL_STORAGE || ':' !in treeDocumentId) return null
        val volume = treeDocumentId.substringBefore(':')
        val base = when {
            volume == "primary" -> "/storage/emulated/0"
            sdCard.matches(volume) -> "/storage/$volume"
            else -> return null
        }
        val relative = treeDocumentId.substringAfter(':').trim('/')
        if (relative.isEmpty()) return null
        return runCatching { KPath.Local("$base/$relative").absolute }.getOrNull()
    }

    /** True for WhatsApp's (or WhatsApp Business's) own folder, not a parent or a subfolder of it. */
    fun isWhatsAppFolder(treeDocumentId: String): Boolean {
        val relative = treeDocumentId.substringAfter(':', "").trim('/')
        return WHATSAPP.any { it.equals(relative, ignoreCase = true) }
    }

    /** Where the system picker opens (ONB-6). */
    fun initialDocumentId(): String = "primary:${WHATSAPP.first()}"
}
