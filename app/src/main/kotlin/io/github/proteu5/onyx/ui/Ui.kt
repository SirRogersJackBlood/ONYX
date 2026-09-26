// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.proteu5.onyx.OnyxApp
import io.github.proteu5.onyx.R
import io.github.proteu5.onyx.core.ForgeRank

/** ONYX × /FORGE palette: obsidian black, forge-cyan glow, silver type. */
object Forge {
    val BG = Color.parseColor("#000000")
    val SURFACE = Color.parseColor("#0A1015")
    val LINE = Color.parseColor("#12303A")
    val CYAN = Color.parseColor("#7FE3FF")
    val CYAN_DIM = Color.parseColor("#2C6E80")
    val TEXT = Color.parseColor("#E8F6FA")
    val MUTED = Color.parseColor("#7C8C95")
    val SILVER = Color.parseColor("#C9CED3")
    val WARN = Color.parseColor("#FFB86B")
    val MONO: Typeface = Typeface.MONOSPACE

    fun coverDrawable(c: ForgeRank.Cover): Int? = when (c) {
        ForgeRank.Cover.EMBLEM -> R.drawable.onyx_emblem
        ForgeRank.Cover.SIGIL -> R.drawable.forge_sigil
        ForgeRank.Cover.RING -> R.drawable.ic_launcher_foreground
        ForgeRank.Cover.NONE -> null
    }
}

fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.text(s: CharSequence, size: Float = 15f, color: Int = Forge.TEXT, mono: Boolean = false, bold: Boolean = false) =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = if (mono) Forge.MONO else Typeface.create("sans-serif-light", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

fun Context.button(label: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
    text = label; isAllCaps = false; textSize = 15f
    setTextColor(if (primary) Forge.BG else Forge.CYAN)
    background = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        if (primary) setColor(Forge.CYAN) else { setColor(Color.TRANSPARENT); setStroke(dp(1), Forge.CYAN_DIM) }
    }
    setPadding(dp(8), dp(12), dp(8), dp(12))
    // Never wrap a label onto two lines ("RENAM / E"): keep one line and let the text
    // shrink (15sp down to 10sp) to fit narrow phones or large system font settings.
    isSingleLine = true
    maxLines = 1
    setAutoSizeTextTypeUniformWithConfiguration(10, 15, 1, TypedValue.COMPLEX_UNIT_SP)
    setOnClickListener { onClick() }
}

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(14), dp(16), dp(14))
    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Forge.SURFACE); setStroke(dp(1), Forge.LINE) }
}

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun LinearLayout.add(v: View, topMargin: Int = 0): LinearLayout {
    addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        this.topMargin = context.dp(topMargin)
    }); return this
}

fun Context.image(res: Int, sizeDp: Int) = ImageView(this).apply {
    setImageResource(res); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
}

/**
 * Every ONYX screen: screenshots/recents thumbnails blocked (FLAG_SECURE), black theme,
 * and the optional app lock (biometric or device credential).
 */
abstract class OnyxActivity : Activity() {
    protected val app: OnyxApp get() = application as OnyxApp
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.setBackgroundColor(Forge.BG)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
    }

    /**
     * Android 15+ (targetSdk 35+) forces edge-to-edge: content draws under the status bar,
     * the navigation bar (|||, square, <) and the keyboard. Pad every screen's root by those
     * insets so nothing, especially the send bar, is hidden behind them.
     */
    override fun setContentView(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        super.setContentView(view)
        view.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        if (app.appLockEnabled && !app.unlocked && !prompting) requireUnlock()
    }

    private fun requireUnlock() {
        val bm = getSystemService(BiometricManager::class.java)
        val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (bm.canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { app.unlocked = true; return }
        prompting = true
        window.decorView.visibility = View.INVISIBLE
        BiometricPrompt.Builder(this)
            .setTitle("Unlock ONYX")
            .setAllowedAuthenticators(auth)
            .build()
            .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false; app.unlocked = true; window.decorView.visibility = View.VISIBLE
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false; finishAffinity()
                }
            })
    }

    /** Standard screen: scrollable column on black with a header. */
    protected fun screen(title: String, subtitle: String? = null, build: LinearLayout.() -> Unit): LinearLayout {
        val col = vbox().apply { setPadding(dp(18), dp(22), dp(18), dp(28)) }
        col.add(text(title, 26f, Forge.SILVER).apply { letterSpacing = 0.25f })
        subtitle?.let { col.add(text(it, 12f, Forge.CYAN, mono = true).apply { letterSpacing = 0.15f }, 2) }
        col.build()
        setContentView(ScrollView(this).apply { setBackgroundColor(Forge.BG); isFillViewport = true; addView(col) })
        return col
    }
}

/** Public links. Set SITE_URL once the site is deployed (e.g. your Railway domain). */
object Links {
    const val SITE_URL = "https://0nyx.up.railway.app"
}

/** Renders a QR code (dark modules on light background: scans reliably on every camera). */
fun qrBitmap(text: String, size: Int): Bitmap {
    val hints = mapOf(
        com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
        com.google.zxing.EncodeHintType.MARGIN to 2,
    )
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
    val light = Color.parseColor("#E8F6FA")
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else light }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** Opens the Android share sheet. The text leaves ONYX, so callers must warn first. */
fun Activity.shareText(subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, subject))
}
