package com.mohamed.audioshelf

import java.util.Locale

/**
 * Chapters are individual Media3 items (including clipped chapters of one M4B).
 * MediaController.seekTo(index, positionMs) therefore uses chapter-relative ms.
 */
internal object StorySeek {
    data class Target(val chapterIndex: Int, val positionMs: Long)

    /**
     * Traverse known chapter boundaries for skip actions. Unknown durations
     * never produce guessed positions in other chapters.
     */
    fun byOffset(
        durationsMs: List<Long>,
        currentIndex: Int,
        currentPositionMs: Long,
        offsetMs: Long,
    ): Target {
        if (durationsMs.isEmpty()) return Target(0, 0)
        var index = currentIndex.coerceIn(durationsMs.indices)
        var position = currentPositionMs.coerceAtLeast(0)
        val initialDuration = durationsMs[index]
        if (initialDuration > 0) position = position.coerceAtMost(initialDuration)
        if (offsetMs == 0L) return Target(index, position)

        var left = if (offsetMs == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(offsetMs)
        if (offsetMs > 0L) {
            while (left > 0L) {
                val duration = durationsMs[index]
                if (duration <= 0L) {
                    return Target(index, (position + left).coerceAtLeast(position))
                }
                val remaining = (duration - position).coerceAtLeast(0)
                if (left < remaining) return Target(index, position + left)
                if (index == durationsMs.lastIndex) return Target(index, duration)
                left -= remaining
                index++
                position = 0L
                if (left == 0L) return Target(index, position)
            }
        } else {
            while (left > 0L) {
                if (left <= position) return Target(index, position - left)
                left -= position
                if (index == 0) return Target(0, 0)
                val previousDuration = durationsMs[index - 1]
                if (previousDuration <= 0L) return Target(index, 0)
                index--
                position = previousDuration
            }
        }
        return Target(index, position)
    }

    fun clock(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1000
        val hours = seconds / 3600
        val minutes = seconds / 60 % 60
        val rem = seconds % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rem)
        else String.format(Locale.US, "%d:%02d", minutes, rem)
    }
}
