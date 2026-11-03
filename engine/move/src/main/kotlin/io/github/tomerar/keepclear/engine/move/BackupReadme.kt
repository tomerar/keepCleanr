// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.move

/**
 * Owner brief H1 item 10: a small `README.html` next to the manifest, so family can open the backup folder on a
 * computer and understand it. It is **not user data**: it never counts as a backed-up file, is never verified as one,
 * is never listed in the manifest, and the restore never reads it ([Manifest.isReserved]). KeepClear only ever
 * replaces it with a new version on the next backup.
 *
 * The page is self-contained: no scripts, no styles from elsewhere, no links, no images. Every text is escaped
 * ([escape]); the texts come from the app's string resources (English and Hebrew), the device name from the job.
 */
object BackupReadme {
    const val NAME = "README.html"

    /** The texts of one language. [dir] is "ltr" or "rtl". */
    data class Texts(
        val lang: String,
        val dir: String,
        val title: String,
        val what: String,
        val open: String,
        val restore: String,
    )

    /** HTML-escapes [s] for text and attribute values (`&`, `<`, `>`, `"`, `'`), and drops control characters. */
    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&#39;")
                c == '\n' -> append(' ')
                c.isISOControl() -> Unit
                else -> append(c)
            }
        }
    }

    /** The page for the backup folder of [deviceName], one section per language in [languages]. */
    fun html(deviceName: String, languages: List<Texts>): String = buildString {
        append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>KeepClear · ").append(escape(deviceName)).append("</title>\n")
        append("</head>\n<body>\n")
        for (t in languages) {
            val dir = if (t.dir == "rtl") "rtl" else "ltr"
            append("<section lang=\"").append(escape(t.lang)).append("\" dir=\"").append(dir).append("\">\n")
            append("<h1>").append(escape(t.title)).append("</h1>\n")
            append("<p><bdi>").append(escape(deviceName)).append("</bdi></p>\n")
            append("<p>").append(escape(t.what)).append("</p>\n")
            append("<p>").append(escape(t.open)).append("</p>\n")
            append("<p>").append(escape(t.restore)).append("</p>\n")
            append("</section>\n<hr>\n")
        }
        append("</body>\n</html>\n")
    }
}
