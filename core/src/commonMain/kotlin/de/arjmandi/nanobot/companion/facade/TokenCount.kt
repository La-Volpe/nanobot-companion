package de.arjmandi.nanobot.companion.facade

import kotlin.math.roundToLong

/**
 * The WebUI's token copy, copied rather than invented (T36): a million must read `1M`, not
 * `1000k`, on every surface that prints a window.
 *
 * `window` and `modelWindow` are `formatContextWindow` and `formatModelContextWindow` from
 * `webui/src/lib/model-context-format.ts`; `compact` is `formatCompactTokenCount` from
 * `webui/src/lib/format.ts`. The exact maps exist because the sizes that matter are powers of
 * two, and 65 536 would otherwise print as the `65.5K` its arithmetic gives.
 */
public object TokenCount {

    /** ≥1M in millions, ≥1k in thousands, one decimal where the number has one. */
    public fun window(tokens: Int): String = when {
        tokens >= 1_000_000 -> decimal(tokens.toDouble() / 1_000_000, keepZero = true) + "M"
        tokens >= 1_000 -> decimal(tokens.toDouble() / 1_000, keepZero = true) + "K"
        else -> tokens.toString()
    }

    /** A model's own window: the three sizes the WebUI answers exactly, then the same arithmetic. */
    public fun modelWindow(tokens: Int): String = when (tokens) {
        65_536 -> "64K"
        262_144 -> "256K"
        1_048_576 -> "1M"
        else -> window(tokens)
    }

    /** A running count: `999`, `15.5K`, `155K`, `1.5M` — one decimal below 100k, then whole. */
    public fun compact(tokens: Long): String = when {
        tokens < 1_000 -> tokens.toString()
        tokens < 1_000_000 -> decimal(tokens.toDouble() / 1_000, keepZero = false, digits(tokens < 100_000)) + "K"
        else -> decimal(tokens.toDouble() / 1_000_000, keepZero = false, digits(tokens < 100_000_000)) + "M"
    }

    private fun digits(oneDecimal: Boolean): Int = if (oneDecimal) 1 else 0

    /**
     * `toFixed` and `Number(...)`: a whole value prints without a decimal, and [keepZero] is what
     * `formatContextWindow` does with a `1.0M` the model map then overrides.
     */
    private fun decimal(value: Double, keepZero: Boolean, places: Int = 1): String {
        if (places == 0) return value.roundToLong().toString()
        val tenths = (value * 10).roundToLong()
        val whole = tenths / 10
        val fraction = tenths % 10
        if (fraction == 0L) return if (keepZero && value != value.toLong().toDouble()) "$whole.0" else whole.toString()
        return "$whole.$fraction"
    }
}
