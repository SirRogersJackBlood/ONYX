// SPDX-License-Identifier: AGPL-3.0-only
package io.github.proteu5.onyx.core

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal SOCKS5 CONNECT client (RFC 1928) that ONLY ever sends hostnames (ATYP 0x03).
 * It never resolves anything locally, so there is no path for a DNS leak, and it refuses
 * any destination that is not a valid v3 .onion address.
 */
object Socks5 {
    class SocksException(msg: String) : IOException(msg)

    fun connect(
        proxyPort: Int,
        onion: OnionAddress,
        port: Int,
        timeoutMs: Int = 120_000,
    ): Socket {
        require(port in 1..65535)
        val s = Socket()
        try {
            s.soTimeout = timeoutMs
            // The Tor SOCKS port is always loopback. Never anything else.
            s.connect(InetSocketAddress("127.0.0.1", proxyPort), 10_000)
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())

            out.write(byteArrayOf(0x05, 0x01, 0x00)) // v5, 1 method, no-auth
            out.flush()
            val ver = inp.readUnsignedByte(); val method = inp.readUnsignedByte()
            if (ver != 5 || method != 0) throw SocksException("proxy refused no-auth")

            val host = onion.hostname.toByteArray(Charsets.US_ASCII)
            val req = ByteWriter(host.size + 7)
                .u8(5).u8(1).u8(0).u8(3).u8(host.size).raw(host).u16(port).toByteArray()
            out.write(req)
            out.flush()

            if (inp.readUnsignedByte() != 5) throw SocksException("bad reply version")
            val rep = inp.readUnsignedByte()
            inp.readUnsignedByte() // RSV
            when (inp.readUnsignedByte()) { // skip BND.ADDR
                1 -> inp.skipNBytesCompat(4)
                3 -> inp.skipNBytesCompat(inp.readUnsignedByte())
                4 -> inp.skipNBytesCompat(16)
                else -> throw SocksException("bad address type")
            }
            inp.skipNBytesCompat(2) // BND.PORT
            if (rep != 0) throw SocksException(describe(rep))
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    private fun DataInputStream.skipNBytesCompat(n: Int) { readFully(ByteArray(n)) }

    /** Includes Tor's extended onion-service error codes (prop304). */
    fun describe(code: Int): String = when (code) {
        1 -> "general failure"
        2 -> "not allowed"
        3 -> "network unreachable"
        4 -> "host unreachable"
        5 -> "connection refused"
        6 -> "TTL expired"
        7 -> "command not supported"
        8 -> "address type not supported"
        0xF0 -> "onion service descriptor not found (contact offline)"
        0xF1 -> "onion service descriptor invalid"
        0xF2 -> "onion service introduction failed"
        0xF3 -> "onion service rendezvous failed"
        0xF4 -> "onion service missing client authorization"
        0xF5 -> "onion service wrong client authorization"
        0xF6 -> "invalid onion address"
        0xF7 -> "onion service introduction timed out"
        else -> "socks error $code"
    }
}
