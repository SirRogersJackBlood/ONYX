// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.proteu5.onyx.core.MessagePolicy
import io.github.proteu5.onyx.data.Message
import io.github.proteu5.onyx.media.ImageCodec

/** Shared by 1:1 and group chats (v1.4.4): image + caption with tappable links. */
object MessageViews {
    const val REQ_IMAGE = 41

    /** Opens the system photo picker. No storage permission: ONYX only ever sees the one picture chosen. */
    fun pickImage(a: Activity) {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        runCatching { a.startActivityForResult(i, REQ_IMAGE) }
    }

    /**
     * Re-encodes the picked image off the UI thread, shows it, and sends only after the user confirms.
     * [send] gets the clean JPEG and the caption.
     */
    fun confirmAndSend(a: Activity, uri: Uri, caption: String, send: (ByteArray, String) -> Unit) {
        Thread {
            val r = runCatching { io.github.proteu5.onyx.media.ImageSandbox.prepare(a, uri) }
            a.runOnUiThread {
                r.onFailure { android.widget.Toast.makeText(a, it.message ?: "Couldn't read that image.", android.widget.Toast.LENGTH_LONG).show() }
                r.onSuccess { jpeg ->
                    val preview = ImageView(a).apply {
                        adjustViewBounds = true; setImageBitmap(ImageCodec.decodeForDisplay(jpeg, 720))
                        setPadding(a.dp(16), a.dp(8), a.dp(16), 0)
                    }
                    AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Send this image?")
                        .setView(preview)
                        .setMessage("Location, camera model, date and every other hidden detail were removed. " +
                            "Re-encoded to ${jpeg.size / 1024} KB." + if (caption.isNotBlank()) "\n\nCaption: $caption" else "")
                        .setPositiveButton("Send") { _, _ -> Thread { send(jpeg, caption) }.start() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }.start()
    }

    /** Adds the message body (image if any, then text with links) to [bubble]. */
    fun fill(a: Activity, bubble: LinearLayout, m: Message, image: () -> ByteArray?) {
        if (m.hasImage) {
            val jpeg = image()
            val bmp: Bitmap? = jpeg?.let { ImageCodec.decodeForDisplay(it, 640) }
            if (bmp != null) {
                bubble.add(ImageView(a).apply {
                    adjustViewBounds = true; maxHeight = a.dp(280); setImageBitmap(bmp)
                    setOnClickListener { showFull(a, jpeg) }
                })
            } else bubble.add(a.text("image unavailable", 12f, Forge.MUTED, mono = true))
        }
        if (m.text.isNotEmpty()) bubble.add(linkified(a, m.text), if (m.hasImage) 6 else 0)
    }

    private fun showFull(a: Activity, jpeg: ByteArray) {
        val iv = ImageView(a).apply { adjustViewBounds = true; setImageBitmap(ImageCodec.decodeForDisplay(jpeg, 1600)) }
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setView(iv).setPositiveButton("Close", null).show()
    }

    /** Text view where links are tappable but never opened directly. */
    fun linkified(a: Activity, text: String): TextView {
        val tv = a.text(text, 15f, Forge.TEXT)
        val ranges = MessagePolicy.linkRanges(text)
        if (ranges.isEmpty()) return tv
        val sp = SpannableString(text)
        for (r in ranges) {
            val url = text.substring(r)
            sp.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = leavingNotice(a, url)
                override fun updateDrawState(ds: TextPaint) { ds.color = Forge.CYAN; ds.isUnderlineText = true }
            }, r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sp
        tv.movementMethod = LinkMovementMethod.getInstance()
        return tv
    }

    /** "You are leaving ONYX": the full host first, then a choice. Nothing is fetched until the user says so. */
    fun leavingNotice(a: Activity, url: String) {
        val host = MessagePolicy.linkHost(url) ?: return
        val plain = url.startsWith("http://")
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("You are leaving ONYX")
            .setMessage("This opens a website in your browser:\n\n$host\n\n$url\n\n" +
                "The site will see your IP address and can track you. ONYX's protection ends here." +
                (if (plain) "\n\n⚠ Not encrypted (http): anyone on your network can see and change this page." else "") +
                "\n\nFor more privacy, open it in Tor Browser: copy the link instead.")
            .setPositiveButton("Open") { _, _ -> runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            .setNeutralButton("Copy link") { _, _ ->
                a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("link", url))
                android.widget.Toast.makeText(a, "Copied. Other apps can read the clipboard: clear it when done.", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Stay", null).show()
    }
}
