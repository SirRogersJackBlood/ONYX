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
 *  6. Links (v1.4.4) are the one exception to 4 and 5: an http(s) link to a plain ASCII host name is kept
 *     as typed, minus tracking parameters (utm_*, fbclid, gclid…). A link with a user part
 *     ("https://bank.com@evil.example") or a non-ASCII host is not treated as a link. At most [MAX_LINKS].
 *     The app never fetches a link or shows a preview; tapping one asks first ("You are leaving ONYX").
 *  7. At most [MAX_CHARS] characters (code points, never splitting an emoji).
 *
 * There are no attachments except images, which are re-encoded without metadata (see Media).
 */
object MessagePolicy {
    const val MAX_CHARS = 500
    const val MAX_LINKS = 3
    const val MAX_LINK_CHARS = 300
    private const val ALLOWED_PUNCT = ".,!?'\"-:()"

    /** Returns the sanitized text, or "" if nothing acceptable remains. */
    fun sanitize(input: String): String {
        val norm = Normalizer.normalize(input, Normalizer.Form.NFKC)
        val out = StringBuilder(minOf(norm.length, MAX_CHARS * 2))
        var count = 0
        var lastWasSymbol = false     // last kept non-space char was punctuation/emoji
        var lastWasSpace = true       // also trims leading spaces
        var i = 0
        var links = 0
        while (i < norm.length && count < MAX_CHARS) {
            // Links: kept verbatim (cleaned) when they pass the checks in cleanLink.
            if (links < MAX_LINKS && (norm.startsWith("http://", i, ignoreCase = true) || norm.startsWith("https://", i, ignoreCase = true))) {
                var j = i
                while (j < norm.length && j - i < MAX_LINK_CHARS + 1 && norm[j].code < 128 && URL_CHARS.indexOf(norm[j]) >= 0) j++
                val raw = trimLinkEnd(norm.substring(i, j))
                val link = cleanLink(raw)
                if (link != null && count + link.length <= MAX_CHARS) {
                    out.append(link); count += link.length; links++
                    lastWasSymbol = false; lastWasSpace = false
                    i += raw.length
                    continue
                }
            }
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

    // Deliberately narrower than RFC 3986: no ' ; * ! $ [ ] in links, so a "link" can't carry code-shaped text.
    private const val URL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#@&=%()+,"
    private val LINK = Regex("^(https?)://([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+)(:[0-9]{1,5})?([/?#].*)?$", RegexOption.IGNORE_CASE)
    private val TRACKING = setOf("fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "igshid", "mc_eid", "mc_cid", "_hsenc", "_hsmi", "si", "ttclid", "li_fat_id")

    /** Sentence punctuation right after a link belongs to the sentence, not the link. */
    private fun trimLinkEnd(raw: String): String {
        var s = raw
        while (s.isNotEmpty()) {
            val c = s.last()
            if (c in ".,!?;:'\"" || (c == ')' && s.count { it == ')' } > s.count { it == '(' })) s = s.dropLast(1) else break
        }
        return s
    }

    /**
     * Returns the link as it will be sent, or null if it isn't one ONYX accepts: http(s) only, ASCII host name
     * with a dot, no "user@" part (the classic "real.com@evil.example" trick), at most [MAX_LINK_CHARS].
     * Tracking parameters are removed; scheme and host are lower-cased.
     */
    fun cleanLink(raw: String): String? {
        if (raw.length > MAX_LINK_CHARS) return null
        val m = LINK.matchEntire(raw) ?: return null
        val scheme = m.groupValues[1].lowercase(); val host = m.groupValues[2].lowercase()
        val port = m.groupValues[3]; var rest = m.groupValues[4]
        val authorityEnd = raw.indexOfAny(charArrayOf('/', '?', '#'), raw.indexOf("://") + 3).let { if (it < 0) raw.length else it }
        if ('@' in raw.substring(0, authorityEnd)) return null
        val hash = rest.indexOf('#').let { if (it < 0) "" else rest.substring(it) }
        rest = rest.removeSuffix(hash)
        val q = rest.indexOf('?')
        if (q >= 0) {
            val kept = rest.substring(q + 1).split('&').filter { p ->
                val name = p.substringBefore('=').lowercase()
                p.isNotEmpty() && !name.startsWith("utm_") && name !in TRACKING
            }
            rest = rest.substring(0, q) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        }
        return "$scheme://$host$port$rest$hash"
    }

    /** Links in already-sanitized text, as (start, endExclusive) ranges, for the UI to make tappable. */
    fun linkRanges(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length && out.size < MAX_LINKS) {
            val at = listOf(text.indexOf("http://", i), text.indexOf("https://", i)).filter { it >= 0 }.minOrNull() ?: break
            var j = at
            while (j < text.length && text[j].code < 128 && URL_CHARS.indexOf(text[j]) >= 0) j++
            val raw = trimLinkEnd(text.substring(at, j))
            if (cleanLink(raw) == raw) out += at until at + raw.length
            i = maxOf(at + raw.length, at + 1)
        }
        return out
    }

    /** The host a link opens, shown in the "You are leaving ONYX" notice. */
    fun linkHost(link: String): String? = LINK.matchEntire(link)?.groupValues?.get(2)?.lowercase()

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
