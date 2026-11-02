// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import javax.tools.ToolProvider

/** D6 (owner review 2026-11-02): the bytecode check catches network use that an import-line check can't see. */
class ForbiddenApisTest {
    @TempDir
    lateinit var dir: File

    /** Compiles Java sources with the JDK's own compiler; returns class name → bytes. */
    private fun compile(vararg sources: Pair<String, String>): Map<String, ByteArray> {
        val run = File(dir, "run${dir.list()!!.size}")
        val src = File(run, "src").apply { mkdirs() }
        val out = File(run, "out").apply { mkdirs() }
        val files = sources.map { (name, code) -> File(src, "$name.java").apply { writeText(code) } }
        val javac = ToolProvider.getSystemJavaCompiler() ?: error("tests need a JDK with javac")
        assertEquals(0, javac.run(null, null, null, "-d", out.path, *files.map { it.path }.toTypedArray()))
        return ForbiddenApis.load(listOf(out), emptyList())
    }

    /** The import-line rule the Konsist architecture test uses (testing/architecture). */
    private val importRule = Regex(
        """import (java\.net\.(Socket|ServerSocket|URL|HttpURLConnection|DatagramSocket)|javax\.net\.|""" +
            """okhttp3\.|com\.hierynomus\.|android\.net\.http|java\.net\.http\.)""",
    )

    @Test
    fun `a fully qualified Socket, with no import line, is caught in the bytecode`() {
        val code = """
            class Leak {
                Object open() throws Exception { return new java.net.Socket("example.com", 80); }
            }
        """.trimIndent()
        assertFalse(importRule.containsMatchIn(code), "the import-line check sees nothing")
        val violations = ForbiddenApis.check(":app", compile("Leak" to code))
        assertTrue(violations.any { "java.net.Socket" in it }, violations.toString())
    }

    @Test
    fun `URL and URLConnection reached through a method chain, or only in a field type, are caught`() {
        val code = """
            class Fetch {
                java.net.URLConnection last;
                Object get() throws Exception { return new java.net.URI("https://x").toURL().openStream(); }
            }
        """.trimIndent()
        val violations = ForbiddenApis.check(":core:common", compile("Fetch" to code))
        assertTrue(violations.any { "java.net.URL:" in it }, violations.toString())
        assertTrue(violations.any { "java.net.URLConnection" in it }, violations.toString())
    }

    @Test
    fun `backup net may use sockets, nobody may refer to the test-only host rule, a string is not a use`() {
        val sock = compile(
            "Ok" to """
                class Ok {
                    String name = "java/net/Socket";
                    Object open() { return new java.net.Socket(); }
                }
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), ForbiddenApis.check(":backup:net", sock))
        // Only the string: not flagged anywhere.
        val text = compile("Text" to """class Text { String s = "java.net.Socket okhttp3.OkHttpClient"; }""")
        assertEquals(emptyList<String>(), ForbiddenApis.check(":app", text))

        val testRule = "io/github/tomerar/keepclear/backup/net/TestsOnThisDevice"
        val user = synthetic("io/github/tomerar/keepclear/App", referring = testRule)
        assertTrue(ForbiddenApis.check(":app", mapOf("App.class" to user)).single().contains("only tests"))
        assertTrue(ForbiddenApis.check(":backup:net", mapOf("App.class" to user)).isNotEmpty())
        // The class itself (and its nested classes) may mention itself.
        val itself = synthetic(testRule, referring = "$testRule\$Companion")
        assertEquals(emptyList<String>(), ForbiddenApis.check(":backup:net", mapOf("T.class" to itself)))
    }

    @Test
    fun `OkHttp outside backup net is caught even without the library on the classpath`() {
        val client = synthetic("io/github/tomerar/keepclear/Sneaky", referring = "okhttp3/OkHttpClient")
        val violations = ForbiddenApis.check(":backup:target-smb", mapOf("Sneaky.class" to client))
        assertTrue(violations.single().contains("okhttp3.OkHttpClient"), violations.toString())
        assertEquals(emptyList<String>(), ForbiddenApis.check(":backup:net", mapOf("Sneaky.class" to client)))
        val webView = synthetic("io/github/tomerar/keepclear/Web", referring = "android/webkit/WebView\$HitTestResult")
        assertTrue(ForbiddenApis.check(":backup:net", mapOf("Web.class" to webView)).isNotEmpty())
    }

    /** A class whose only method does `new <referring>()` (the referred class needn't exist). */
    private fun synthetic(name: String, referring: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "use", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, referring)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, referring, "<init>", "()V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
}
