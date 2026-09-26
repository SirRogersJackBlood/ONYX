// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

import java.text.Normalizer

/**
 * ONYX is for short human conversation, not a data or command channel.
 *
 * Every text message is sanitized on SEND and again on RECEIVE (a modified or older client
 * could send anything):
 *  1. NFKC-normalized, so look-alikes such as fullwidth "＜" or "＄" become the ASCII they imitate
 *     and are then filtered like the real thing.
 *  2. Control, format and invisible characters are removed: zero-width joiners, bidi overrides
 *     (used to disguise text), BOMs and the like.
 *  3. Line breaks and tabs become a single space: one line only, no pasted scripts.
 *  4. Only letters, marks, digits, spaces, emoji and the punctuation . , ! ? ' " - : ( ) survive.
 *     Code-shaped symbols are dropped: < > { } [ ] ` $ \ | ; = # % & * _ @ / ^ ~ + and friends.
 *  5. No two symbols in a row (ignoring spaces): "!!!" → "!", "--" → "-", "?>" → "?".
 *  6. At most [MAX_CHARS] characters (code points, never splitting an emoji).
 *
 * There are no attachments: the only message kinds are TEXT, BADGE, RECEIPT, PAIRED and TIMER,
 * and unknown kinds are rejected when decoded.
 */
object MessagePolicy {
    const val MAX_CHARS = 80
    private const val ALLOWED_PUNCT = ".,!?'\"-:()"

    /** Returns the sanitized text, or "" if nothing acceptable remains. */
    fun sanitize(input: String): String {
        val norm = Normalizer.normalize(input, Normalizer.Form.NFKC)
        val out = StringBuilder(minOf(norm.length, MAX_CHARS * 2))
        var count = 0
        var lastWasSymbol = false     // last kept non-space char was punctuation/emoji
        var lastWasSpace = true       // also trims leading spaces
        var i = 0
        while (i < norm.length && count < MAX_CHARS) {
            val cp = norm.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)

            if (cp == '\n'.code || cp == '\r'.code || cp == '\t'.code || Character.isSpaceChar(cp)) {
                if (!lastWasSpace) { out.append(' '); count++; lastWasSpace = true }
                continue
            }
            val isWordChar = Character.isLetterOrDigit(cp) ||
                type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            val isAllowedPunct = cp < 128 && ALLOWED_PUNCT.indexOf(cp.toChar()) >= 0
            val isEmoji = type == Character.OTHER_SYMBOL.toInt() && cp >= 0x2190   // pictographs, not ASCII-ish symbols

            when {
                isWordChar -> { out.appendCodePoint(cp); count++; lastWasSymbol = false; lastWasSpace = false }
                isAllowedPunct || isEmoji -> {
                    if (lastWasSymbol) continue                        // no consecutive symbols
                    out.appendCodePoint(cp); count++; lastWasSymbol = true; lastWasSpace = false
                }
                else -> Unit                                           // dropped: controls, format chars, code symbols
            }
        }
        return out.toString().trim()
    }

    /** True if [text] is already exactly what [sanitize] would produce (used to warn in the UI). */
    fun isClean(text: String): Boolean = sanitize(text) == text

    /**
     * Outgoing rate limit: short bursts are fine, scripted bulk sending is not.
     * Returns true if a message may be sent now, given the send times of the last minute.
     */
    fun rateAllowed(recentSendTimesMs: List<Long>, nowMs: Long, perMinute: Int = 20, minGapMs: Long = 700): Boolean {
        val lastMinute = recentSendTimesMs.filter { nowMs - it < 60_000 }
        if (lastMinute.size >= perMinute) return false
        val last = lastMinute.maxOrNull() ?: return true
        return nowMs - last >= minGapMs
    }
}
