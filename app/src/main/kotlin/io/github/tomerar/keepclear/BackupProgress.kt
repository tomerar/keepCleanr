// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear

import android.content.Context
import io.github.tomerar.keepclear.core.common.ByteSize
import io.github.tomerar.keepclear.core.i18n.R
import java.util.Locale
import kotlin.math.exp

/**
 * Owner brief H1 item 4: the time left of the running job from a **smoothed** transfer rate. Files finish in steps
 * (a large video, then many small photos), so the rate between two reloads jumps; an exponentially weighted average
 * with a time constant of [tauMillis] keeps "about 41 minutes" from jumping with it.
 *
 * Review PR C / E13: it starts again whenever a job (re)starts, so time spent paused never stretches the estimate;
 * nothing is said in the first [warmUpMillis] (not enough to be honest).
 */
class EtaTracker(private val tauMillis: Long = 30_000, private val warmUpMillis: Long = 10_000) {
    private var running: String? = null
    private var jobId: String? = null
    private var startAt = 0L
    private var startBytes = 0L
    private var lastAt = 0L
    private var lastBytes = 0L

    /** Bytes per millisecond, smoothed; null until [warmUpMillis] have passed with progress. */
    private var rate: Double? = null

    fun onRunning(id: String?) {
        if (id != running) {
            jobId = null
            running = id
        }
    }

    fun estimate(jobId: String, now: Long, doneBytes: Long, totalBytes: Long): Long? {
        if (this.jobId != jobId) {
            this.jobId = jobId
            startAt = now
            startBytes = doneBytes
            lastAt = now
            lastBytes = doneBytes
            rate = null
            return null
        }
        val dt = now - lastAt
        if (dt > 0) {
            val current = rate
            rate = if (current == null) {
                val elapsed = now - startAt
                val moved = doneBytes - startBytes
                if (elapsed >= warmUpMillis && moved > 0) moved.toDouble() / elapsed else null
            } else {
                val instant = (doneBytes - lastBytes).coerceAtLeast(0).toDouble() / dt
                val alpha = 1 - exp(-dt.toDouble() / tauMillis)
                current + alpha * (instant - current)
            }
            lastAt = now
            lastBytes = doneBytes
        }
        val r = rate ?: return null
        if (r <= 0.0) return null
        return ((totalBytes - doneBytes).coerceAtLeast(0) / r).toLong()
    }
}

/**
 * Owner brief H1 item 5: TalkBack hears the progress in steps of about 10 %, not on every file: the step (0…10) the
 * screen's live region announces.
 */
fun announceStep(doneBytes: Long, totalBytes: Long): Int =
    if (totalBytes <= 0) 0 else ((doneBytes.coerceAtLeast(0) * 10) / totalBytes).toInt().coerceIn(0, 10)

/** "42%" in the user's language (the notification's short critical text, the live region). */
fun percentText(doneBytes: Long, totalBytes: Long, locale: Locale): String {
    val fraction = if (totalBytes <= 0) 0.0 else (doneBytes.coerceAtLeast(0).toDouble() / totalBytes).coerceIn(0.0, 1.0)
    // Floor, so "100%" means done.
    val floored = kotlin.math.floor(fraction * 100) / 100
    return java.text.NumberFormat.getPercentInstance(locale).format(floored)
}

/** "2 hours", "15 minutes" in the user's language (review PR C); also used outside Compose (the notification). */
fun durationText(context: Context, millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(1)
    val res = context.resources
    return if (minutes >= 90) {
        val h = ((minutes + 30) / 60).toInt()
        res.getQuantityString(R.plurals.duration_hours, h, count(context, h.toLong()))
    } else {
        res.getQuantityString(R.plurals.duration_minutes, minutes.toInt(), count(context, minutes))
    }
}

private fun count(context: Context, n: Long) = io.github.tomerar.keepclear.core.common.Bidi.isolate(
    java.text.NumberFormat.getIntegerInstance(context.resources.configuration.locales[0]).format(n),
)

/**
 * Owner brief H1 item 4: ONE progress line, "12.9 of 31.0GB · about 41 minutes left" (the time only once it can be
 * said honestly). The phase (copying / checking) is a separate small label.
 */
fun backupProgressLine(context: Context, doneBytes: Long, totalBytes: Long, etaMillis: Long?): String {
    val locale = context.resources.configuration.locales[0]
    val (done, total) = ByteSize.progress(doneBytes, totalBytes, locale)
    return if (etaMillis == null) {
        context.getString(R.string.move_progress_bytes, done, total)
    } else {
        context.getString(R.string.backup_progress_eta, done, total, durationText(context, etaMillis))
    }
}
