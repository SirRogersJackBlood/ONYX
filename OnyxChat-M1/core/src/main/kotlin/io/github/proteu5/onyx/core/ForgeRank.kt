// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

/**
 * Snowflake contributor ranking.
 *
 * Privacy rules (enforced here, not just in UI):
 *  - Raw stats (hours, people helped) never leave the device.
 *  - Only the TIER and the user's chosen cover icon are shared, and only inside the
 *    end-to-end-encrypted session with people the user chats with.
 *  - There is no global leaderboard and no server.
 */
object ForgeRank {
    enum class Tier(val code: Int, val title: String, val minHours: Long, val minHelped: Long) {
        NONE(0, "Unlit", 0, 0),
        EMBER(1, "Ember", 0, 0),
        SPARK(2, "Spark", 10, 25),
        FLAME(3, "Flame", 50, 250),
        FORGE(4, "Forge", 200, 1_000),
        CRUCIBLE(5, "Crucible", 1_000, 5_000);

        companion object { fun of(code: Int) = values().firstOrNull { it.code == code } ?: NONE }
    }

    data class Stats(val secondsProxied: Long, val peopleHelped: Long, val everEnabled: Boolean)

    /** A tier is reached by EITHER hours OR people helped, whichever comes first. */
    fun tierFor(s: Stats): Tier {
        if (!s.everEnabled) return Tier.NONE
        val hours = s.secondsProxied / 3600
        return Tier.values().filter { it.code >= Tier.EMBER.code }
            .last { hours >= it.minHours || s.peopleHelped >= it.minHelped }
    }

    /** Progress toward the next tier in [0,1]. */
    fun progress(s: Stats): Double {
        val cur = tierFor(s)
        val next = Tier.values().firstOrNull { it.code == cur.code + 1 } ?: return 1.0
        val h = (s.secondsProxied / 3600.0) / next.minHours.coerceAtLeast(1)
        val p = s.peopleHelped.toDouble() / next.minHelped.coerceAtLeast(1)
        return maxOf(h, p).coerceIn(0.0, 1.0)
    }

    /** Available cover icons (ids map to drawables in the app). */
    enum class Cover(val code: Int) { EMBLEM(0), SIGIL(1), RING(2), NONE(255);
        companion object { fun of(c: Int) = values().firstOrNull { it.code == c } ?: NONE } }

    /** The ONLY data about contribution that is ever sent, and only to chat partners. */
    data class Badge(val tier: Tier, val cover: Cover) {
        fun encode(): ByteArray = byteArrayOf(1, tier.code.toByte(), cover.code.toByte())
        companion object {
            fun decode(b: ByteArray): Badge {
                if (b.size != 3 || b[0] != 1.toByte()) throw MalformedException("badge")
                return Badge(Tier.of(b[1].toInt() and 0xff), Cover.of(b[2].toInt() and 0xff))
            }
        }
    }

    /** Snowflake runs only when all guardrails pass. */
    data class Guardrails(
        val userEnabled: Boolean,
        val onUnmeteredWifi: Boolean,
        val charging: Boolean,
        val bytesToday: Long,
        val dailyCapBytes: Long,
    ) {
        fun allowed() = userEnabled && onUnmeteredWifi && charging && bytesToday < dailyCapBytes
    }
}
