// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import io.github.proteu5.onyx.core.Bytes

/**
 * Accessibility services can read everything on screen (decrypted messages included) and act for the user.
 * Screen readers need that; malware abuses it. ONYX can't tell them apart reliably, so it says which ones are
 * on (v1.4.4). On Android 14+ every ONYX screen is also marked "accessibility data sensitive"
 * (see [OnyxActivity]): only services that declare themselves accessibility TOOLS (TalkBack, Switch Access…)
 * can read it.
 */
object AccessibilityCheck {
    class Service(val id: String, val label: String, val isTool: Boolean)

    fun enabled(ctx: Context): List<Service> = runCatching {
        val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return emptyList()
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).map { info ->
            Service(
                info.id ?: "?",
                runCatching { info.resolveInfo.loadLabel(ctx.packageManager).toString() }.getOrDefault(info.id ?: "Unknown app"),
                Build.VERSION.SDK_INT >= 31 && info.isAccessibilityTool,
            )
        }
    }.getOrDefault(emptyList())

    /** Changes whenever the set of enabled services changes, so a dismissed warning comes back for a new one. */
    fun fingerprint(list: List<Service>): String =
        Bytes.hex(Bytes.sha256(list.map { it.id }.sorted().joinToString("\n").toByteArray())).take(32)

    /** True on Android 14+, where ONYX hides its screens from services that aren't accessibility tools. */
    val sensitiveScreensSupported: Boolean get() = Build.VERSION.SDK_INT >= 34
}
