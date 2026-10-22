package ir.payam.messenger

import ir.payam.messenger.core.Attach
import ir.payam.messenger.core.FileGuard
import ir.payam.messenger.core.Ids
import ir.payam.messenger.core.Md
import ir.payam.messenger.core.Pw
import ir.payam.messenger.core.isNewerVersion
import ir.payam.messenger.ui.Invite
import ir.payam.messenger.ui.inviteFromAppLink
import ir.payam.messenger.ui.parseInvite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreTest {
    private fun tmp(name: String, bytes: ByteArray) = File.createTempFile("fguard", name).apply { writeBytes(bytes); deleteOnExit() }
    private fun zip(vararg entries: String) = File.createTempFile("fguard", ".zip").apply {
        ZipOutputStream(outputStream()).use { z -> entries.forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() } }
        deleteOnExit()
    }

    @Test fun usernameMatchesServer() {
        // Same vector as server.js usernameOf(Buffer.alloc(32,1), Buffer.alloc(32,2))
        assertEquals("v3f2l0ho", Ids.usernameOf(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
    }

    @Test fun safetyNumberIsSymmetric() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        assertEquals(Ids.safetyNumber(a, b), Ids.safetyNumber(b, a))
        assertEquals(12, Ids.safetyNumber(a, b).split(" ").size)
    }

    @Test fun fileGuardAllowsRealFiles() {
        assertTrue(FileGuard.check(tmp(".jpg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())), "a.jpg").ok)
        assertTrue(FileGuard.check(tmp(".pdf", "%PDF-1.7 hello".toByteArray()), "doc.pdf").ok)
        assertTrue(FileGuard.check(tmp(".txt", "سلام دنیا".toByteArray()), "note.txt").ok)
        assertTrue(FileGuard.check(zip("photos/a.jpg", "notes.txt"), "pack.zip").ok)
        assertTrue(FileGuard.check(zip("[Content_Types].xml", "word/document.xml"), "cv.docx").ok)
    }

    @Test fun fileGuardBlocksDangerousFiles() {
        assertFalse("exe renamed to jpg", FileGuard.check(tmp(".jpg", "MZ\u0090\u0000".toByteArray()), "photo.jpg").ok)
        assertFalse("jpeg named .exe", FileGuard.check(tmp(".exe", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())), "x.exe").ok)
        assertFalse("apk", FileGuard.check(zip("AndroidManifest.xml", "classes.dex"), "game.zip").ok)
        assertFalse("zip with apk", FileGuard.check(zip("readme.txt", "setup.apk"), "pack.zip").ok)
        assertFalse("zip with script", FileGuard.check(zip("run.bat"), "pack.zip").ok)
        assertFalse("macro doc", FileGuard.check(zip("[Content_Types].xml", "word/document.xml", "word/vbaProject.bin"), "cv.docx").ok)
        assertFalse("html as txt", FileGuard.check(tmp(".html", "<script>".toByteArray()), "page.html").ok)
        assertFalse("binary as txt", FileGuard.check(tmp(".txt", byteArrayOf(0, 1, 2)), "a.txt").ok)
    }

    @Test fun fileNamesAreCleaned() {
        assertEquals("gpj.exe", FileGuard.cleanName("‮gpj.exe"))
        assertEquals("passwd", FileGuard.cleanName("../../etc/passwd"))
    }

    @Test fun attachmentRoundTripAndTamper() {
        val data = ByteArray(200_000) { (it * 31).toByte() }
        val enc = File.createTempFile("enc", ".bin")
        val out = File.createTempFile("dec", ".bin")
        val key = Attach.encrypt(data.inputStream(), enc)
        Attach.decrypt(enc, key, out)
        assertArrayEquals(data, out.readBytes())

        val bytes = enc.readBytes(); bytes[100] = (bytes[100] + 1).toByte(); enc.writeBytes(bytes)
        try { Attach.decrypt(enc, key, out); fail("tampered attachment must be rejected") } catch (e: SecurityException) { }
    }

    @Test fun backupPasswordRoundTrip() {
        val blob = Pw.encrypt("correct horse".toCharArray(), "secret".toByteArray(), iters = 1000)
        assertEquals("secret", String(Pw.decrypt("correct horse".toCharArray(), blob)))
        try { Pw.decrypt("wrong".toCharArray(), blob); fail("wrong password must fail") } catch (e: Exception) { }
    }

    @Test fun markdownParsing() {
        val (text, ents) = Md.parse("hi **bold** and ||secret|| `x`")
        assertEquals("hi bold and secret x", text)
        assertEquals(listOf("b", "sp", "c"), ents.map { it.t })
        assertEquals("bold", text.substring(ents[0].s, ents[0].e))
        assertEquals(ents, Md.fromJson(Md.toJson(ents)))
    }

    @Test fun invitesKeepTheirServer() {
        val fp = "ab".repeat(32)
        assertEquals(Invite("ab12cd34", fp, "chat.example.com"), parseInvite("https://Chat.Example.com/u/ab12cd34#$fp"))
        assertEquals(Invite("ab12cd34", null, null), parseInvite(" AB12CD34 "))
        assertEquals(null, parseInvite("hello"))
    }

    @Test fun appLinkFromInvitePage() {
        val fp = "cd".repeat(32)
        assertEquals("https://chat.example.com/u/ab12cd34#$fp",
            inviteFromAppLink("payam://invite?u=ab12cd34&s=https%3A%2F%2Fchat.example.com&fp=$fp"))
        assertEquals("https://chat.example.com:8443/u/ab12cd34",
            inviteFromAppLink("payam://invite?u=ab12cd34&s=https%3A%2F%2Fchat.example.com%3A8443"))
        // malformed / hostile input is ignored
        assertEquals(null, inviteFromAppLink("payam://invite?u=../../x&s=https%3A%2F%2Fa.b"))
        assertEquals(null, inviteFromAppLink("payam://invite?u=ab12cd34&s=javascript%3Aalert(1)"))
        assertEquals(null, inviteFromAppLink("payam://other?u=ab12cd34&s=https%3A%2F%2Fa.b"))
        // the result round-trips through the normal invite parser
        assertEquals(Invite("ab12cd34", fp, "chat.example.com"),
            parseInvite(inviteFromAppLink("payam://invite?u=ab12cd34&s=https%3A%2F%2Fchat.example.com&fp=$fp")!!))
    }

    @Test fun updateVersionComparison() {
        assertTrue(isNewerVersion("v1.0.2", "1.0.1"))
        assertTrue(isNewerVersion("v1.10.0", "1.9.9"))   // numeric, not alphabetical
        assertTrue(isNewerVersion("2.0", "1.9.9"))
        assertFalse(isNewerVersion("v1.0.1", "1.0.1"))
        assertFalse(isNewerVersion("v1.0.0", "1.0.1"))
        assertFalse(isNewerVersion("v1.0.1", "1.0.1-dev"))
    }
}