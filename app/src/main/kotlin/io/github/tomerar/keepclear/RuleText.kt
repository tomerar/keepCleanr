// SPDX-License-Identifier: GPL-3.0-or-later
// Generated from core/i18n explanations.xml rule ids; keep in sync (RuleTextTest checks it).
package io.github.tomerar.keepclear

import io.github.tomerar.keepclear.core.i18n.R

/** Typed lookup of a rule's explanation strings (CLS-2), without reflection. */
object RuleText {
    data class Ids(val group: Int, val why: Int, val ifDeleted: Int)

    val byRule: Map<String, Ids> = mapOf(
        "protected" to Ids(R.string.group_protected, R.string.why_protected, R.string.if_deleted_protected),
        "user_kept" to Ids(R.string.group_user_kept, R.string.why_user_kept, R.string.if_deleted_user_kept),
        "user_junk" to Ids(R.string.group_user_junk, R.string.why_user_junk, R.string.if_deleted_user_junk),
        "other_app_bin" to
            Ids(R.string.group_other_app_bin, R.string.why_other_app_bin, R.string.if_deleted_other_app_bin),
        "wa_status_old" to
            Ids(R.string.group_wa_status_old, R.string.why_wa_status_old, R.string.if_deleted_wa_status_old),
        "wa_status_fresh" to
            Ids(R.string.group_wa_status_fresh, R.string.why_wa_status_fresh, R.string.if_deleted_wa_status_fresh),
        "legacy_wa" to Ids(R.string.group_legacy_wa, R.string.why_legacy_wa, R.string.if_deleted_legacy_wa),
        "wa_sent_copy" to Ids(R.string.group_wa_sent_copy, R.string.why_wa_sent_copy, R.string.if_deleted_wa_sent_copy),
        "wa_sent" to Ids(R.string.group_wa_sent, R.string.why_wa_sent, R.string.if_deleted_wa_sent),
        "wa_private" to Ids(R.string.group_wa_private, R.string.why_wa_private, R.string.if_deleted_wa_private),
        "wa_received" to Ids(R.string.group_wa_received, R.string.why_wa_received, R.string.if_deleted_wa_received),
        "wa_other" to Ids(R.string.group_wa_other, R.string.why_wa_other, R.string.if_deleted_wa_other),
        "thumbnails" to Ids(R.string.group_thumbnails, R.string.why_thumbnails, R.string.if_deleted_thumbnails),
        "lost_dir" to Ids(R.string.group_lost_dir, R.string.why_lost_dir, R.string.if_deleted_lost_dir),
        "temp_files" to Ids(R.string.group_temp_files, R.string.why_temp_files, R.string.if_deleted_temp_files),
        "os_junk" to Ids(R.string.group_os_junk, R.string.why_os_junk, R.string.if_deleted_os_junk),
        "zip_extracted" to
            Ids(R.string.group_zip_extracted, R.string.why_zip_extracted, R.string.if_deleted_zip_extracted),
        "zip_partial" to Ids(R.string.group_zip_partial, R.string.why_zip_partial, R.string.if_deleted_zip_partial),
        "screenshots" to Ids(R.string.group_screenshots, R.string.why_screenshots, R.string.if_deleted_screenshots),
        "ai_images" to Ids(R.string.group_ai_images, R.string.why_ai_images, R.string.if_deleted_ai_images),
        "call_recordings" to
            Ids(R.string.group_call_recordings, R.string.why_call_recordings, R.string.if_deleted_call_recordings),
        "old_phone" to Ids(R.string.group_old_phone, R.string.why_old_phone, R.string.if_deleted_old_phone),
        "telegram_saved" to
            Ids(R.string.group_telegram_saved, R.string.why_telegram_saved, R.string.if_deleted_telegram_saved),
        "telegram_docs" to
            Ids(R.string.group_telegram_docs, R.string.why_telegram_docs, R.string.if_deleted_telegram_docs),
        "telegram_legacy" to
            Ids(R.string.group_telegram_legacy, R.string.why_telegram_legacy, R.string.if_deleted_telegram_legacy),
        "app_content" to
            Ids(R.string.group_app_content, R.string.why_app_content, R.string.if_deleted_app_content),
        "camera" to Ids(R.string.group_camera, R.string.why_camera, R.string.if_deleted_camera),
        "big_video" to Ids(R.string.group_big_video, R.string.why_big_video, R.string.if_deleted_big_video),
        "apk_files" to Ids(R.string.group_apk_files, R.string.why_apk_files, R.string.if_deleted_apk_files),
        "old_download" to Ids(R.string.group_old_download, R.string.why_old_download, R.string.if_deleted_old_download),
        "unknown" to Ids(R.string.group_unknown, R.string.why_unknown, R.string.if_deleted_unknown),
        "pictures_media" to
            Ids(R.string.group_pictures_media, R.string.why_pictures_media, R.string.if_deleted_pictures_media),
    )

    fun of(ruleId: String): Ids = byRule[ruleId] ?: byRule.getValue("unknown")
}
