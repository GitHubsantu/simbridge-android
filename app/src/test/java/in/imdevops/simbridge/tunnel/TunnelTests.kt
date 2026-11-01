package `in`.imdevops.simbridge.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfInfoTest {
    private fun header(cls: Int, machine: Int): ByteArray {
        val h = ByteArray(20)
        h[0] = 0x7F; h[1] = 'E'.code.toByte(); h[2] = 'L'.code.toByte(); h[3] = 'F'.code.toByte()
        h[4] = cls.toByte(); h[5] = 1; h[16] = 2
        h[18] = (machine and 0xFF).toByte(); h[19] = (machine shr 8).toByte()
        return h
    }

    @Test fun parsesThe32BitArmHeaderOfUpstreamCloudflared() {
        // First 20 bytes of the real cloudflared-linux-arm 2026.10.4 download.
        val real = "7f454c4601010100000000000000000002002800".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val elf = ElfInfo.parse(real)!!
        assertFalse(elf.is64Bit)
        assertEquals("ARM", elf.architecture)
        assertEquals(listOf("armeabi-v7a", "armeabi"), elf.compatibleAbis)
    }

    @Test fun parsesThe64BitArmHeaderOfUpstreamCloudflared() {
        // First 20 bytes of the real cloudflared-linux-arm64 2026.10.4 download (SHA-256 2b7203b4...).
        val real = "7f454c460201010000000000000000000200b700".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val elf = ElfInfo.parse(real)!!
        assertTrue(elf.is64Bit)
        assertEquals("AArch64", elf.architecture)
        // a 64-bit phone that can also run 32-bit apps accepts it; a pure 32-bit phone (the Poco C51) does not
        assertEquals(BinaryVerdict.OK, BinaryCheck.verdict(true, elf, listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals(BinaryVerdict.WRONG_ARCH, BinaryCheck.verdict(true, elf, listOf("armeabi-v7a", "armeabi")))
    }

    @Test fun rejectsNonElfAndShortInput() {
        assertNull(ElfInfo.parse(ByteArray(20)))
        assertNull(ElfInfo.parse(ByteArray(5)))
    }

    @Test fun verdictMatchesPhoneAbis() {
        val arm32 = ElfInfo.parse(header(1, 0x28))
        val arm64 = ElfInfo.parse(header(2, 0xB7))
        val poco = listOf("armeabi-v7a", "armeabi")
        assertEquals(BinaryVerdict.OK, BinaryCheck.verdict(true, arm32, poco))
        // This is the exact situation behind the old ENOENT: an arm64 binary on a 32-bit phone.
        assertEquals(BinaryVerdict.WRONG_ARCH, BinaryCheck.verdict(true, arm64, poco))
        assertEquals(BinaryVerdict.MISSING, BinaryCheck.verdict(false, null, poco))
        assertEquals(BinaryVerdict.NOT_ELF, BinaryCheck.verdict(true, null, poco))
        assertEquals(BinaryVerdict.OK, BinaryCheck.verdict(true, arm64, listOf("arm64-v8a", "armeabi-v7a")))
    }
}

class CloudflaredLogTest {
    @Test fun connectionsAreCountedAndLost() {
        val t = TunnelTracker()
        assertEquals(TunnelState.Connected(1), t.onLine("2026-10-30T10:00:00Z INF Registered tunnel connection connIndex=0 connection=x location=sin01 protocol=http2"))
        assertEquals(TunnelState.Connected(2), t.onLine("INF Registered tunnel connection connIndex=1 connection=y"))
        assertNull(t.onLine("INF Registered tunnel connection connIndex=1 connection=y"))
        assertEquals(TunnelState.Connected(1), t.onLine("INF Unregistered tunnel connection connIndex=0"))
        assertEquals(TunnelState.Starting, t.onLine("INF Unregistered tunnel connection connIndex=1"))
    }

    @Test fun parsingIgnoresCase() {
        assertEquals(LogEvent.Registered(2), CloudflaredLog.parse("INF registered tunnel connection connIndex=2 location=sin01"))
    }

    @Test fun dnsFailureProducesHintInFailureReason() {
        val t = TunnelTracker()
        t.onLine("ERR Unable to establish connection error=\"lookup region1.v2.argotunnel.com on 127.0.0.1:53: read udp: connection refused\"")
        val reason = t.failureReason(1)
        assertTrue(reason.contains("DNS"))
        assertTrue(reason.contains("exited with code 1"))
    }

    @Test fun tokenProblemIsRecognised() {
        val t = TunnelTracker()
        t.onLine("Provided Tunnel token is not valid.")
        assertTrue(t.hint!!.contains("token"))
    }

    @Test fun redactionRemovesSecrets() {
        val token = "eyJhIjoiMTIzNDU2Nzg5MCIsInQiOiJhYmNkZWYiLCJzIjoic2VjcmV0c2VjcmV0In0="
        assertFalse(LogRedactor.redact("using $token now", listOf(token)).contains(token))
        val long = "A".repeat(100)
        assertFalse(LogRedactor.redact("x $long y", emptyList()).contains(long))
        assertEquals("plain line", LogRedactor.redact("plain line", listOf("short")))
    }
}

class TunnelInputsTest {
    @Test fun hostnames() {
        assertEquals("sms-api.example.com", Hostnames.normalize("  SMS-API.Example.com "))
        listOf("", "localhost", "1.2.3.4", "https://a.example.com", "a.example.com/path", "a_b.example.com", "-a.example.com")
            .forEach { assertNull("should reject '$it'", Hostnames.normalize(it)) }
    }

    @Test fun tokens() {
        assertTrue(TokenFormat.looksValid("a".repeat(60)))
        assertFalse(TokenFormat.looksValid("short"))
        assertFalse(TokenFormat.looksValid("a".repeat(60) + " b"))
    }

    @Test fun resolvConf() {
        assertEquals(
            "nameserver 192.168.1.1\nnameserver 8.8.4.4\nnameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:3 attempts:2\n",
            DnsConfig.resolvConf(listOf("192.168.1.1", "fe80::1", "8.8.4.4", "192.168.1.1")),
        )
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:3 attempts:2\n", DnsConfig.resolvConf(listOf("fe80::1", "garbage")))
        // the public fallback is added once, after the phone's own servers
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), DnsConfig.servers(listOf("8.8.8.8")))
    }

    @Test fun remoteProbeClassification() {
        val ok = RemoteProbeRules.classify("h", 401, """{"error":{"code":"UNAUTHORIZED"}}""", 1)
        assertTrue(ok.ok)
        assertFalse(RemoteProbeRules.classify("h", 401, "<html>Cloudflare Access</html>", 1).ok)
        assertFalse(RemoteProbeRules.classify("h", 530, "", 1).ok)
        assertTrue(RemoteProbeRules.classify("h", 502, "", 1).detail.contains("not connected"))
        assertNotNull(RemoteProbeRules.classify("h", 200, "x", 1).detail)
    }
}
