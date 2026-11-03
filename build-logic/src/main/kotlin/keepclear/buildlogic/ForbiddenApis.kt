// SPDX-License-Identifier: GPL-3.0-or-later
package keepclear.buildlogic

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import java.io.File
import java.util.zip.ZipFile

/**
 * ADR 0008/0011, owner review 2026-11-02 (D6 "network confinement"): network APIs are used only where the network
 * lives, checked on the **compiled classes** of every module, not on import lines. A fully qualified call
 * (`java.net.Socket()` without an import), a type alias, an inline function or a reflection-free use through a
 * supertype all end up as references in the bytecode, which is what this reads (ASM, already on the build's
 * classpath through the Android Gradle Plugin; no new dependency in the app).
 */
object ForbiddenApis {
    /**
     * [name]: an internal class name (`java/net/Socket`, also matching its nested classes) or, ending in `/`, a
     * package prefix. [allowedIn]: the only projects whose main classes may refer to it (empty: none at all).
     */
    data class Rule(val name: String, val allowedIn: Set<String>, val why: String) {
        fun matches(type: String): Boolean = if (name.endsWith("/")) {
            type.startsWith(name)
        } else {
            type == name || type.startsWith("$name$")
        }
    }

    private const val NET = ":backup:net"
    private const val NETWORK = "network code lives only in backup:net (ADR 0008/0011, D6)"

    val rules: List<Rule> = listOf(
        Rule("java/net/Socket", setOf(NET), NETWORK),
        Rule("java/net/ServerSocket", setOf(NET), NETWORK),
        Rule("java/net/DatagramSocket", setOf(NET), NETWORK),
        Rule("java/net/URL", setOf(NET), NETWORK),
        Rule("java/net/URLConnection", setOf(NET), NETWORK),
        Rule("java/net/HttpURLConnection", setOf(NET), NETWORK),
        Rule("javax/net/ssl/HttpsURLConnection", setOf(NET), NETWORK),
        Rule("java/net/http/", setOf(NET), NETWORK),
        Rule("android/webkit/WebView", emptySet(), "no WebView in KeepClear (ADR 0008)"),
        Rule("android/app/DownloadManager", emptySet(), "no DownloadManager in KeepClear (ADR 0008)"),
        Rule("okhttp3/", setOf(NET), "OkHttp is confined to backup:net (W3)"),
        Rule("okio/", setOf(NET), "Okio is confined to backup:net (W3)"),
        Rule(
            "io/github/tomerar/keepclear/backup/net/HostHttpClient",
            setOf(NET, ":backup:target-webdav", ":backup:target-dropbox"),
            "only the HTTP backup targets use the shared HTTP client (W3)",
        ),
        Rule(
            "io/github/tomerar/keepclear/backup/net/TestsOnThisDevice",
            emptySet(),
            "only tests may connect to a server on the device itself (D3)",
        ),
    )

    /**
     * Owner brief H4 item 9: methods that Java 21 added to `List` (`SequencedCollection`). Kotlin compiles
     * `mutableList.removeFirst()` to them when it compiles against JDK 21, and Android before API 35 has none of
     * them: the call crashes with NoSuchMethodError on Android 11–14. Kotlin's own `removeAt(0)`, `first()`,
     * `last()`, `removeFirstOrNull()` and `kotlin.collections.ArrayDeque` are fine.
     */
    val forbiddenCalls: Set<String> = listOf("java/util/List", "java/util/ArrayList", "java/util/SequencedCollection")
        .flatMap { owner ->
            listOf("removeFirst", "removeLast", "getFirst", "getLast", "addFirst", "addLast", "reversed")
                .map { "$owner.$it" }
        }.toSet()

    private const val SEQUENCED = "a Java 21 List method, missing before Android 15 (API 35): use removeAt(0), " +
        "removeAt(lastIndex), first(), last() or kotlin.collections.ArrayDeque (owner brief H4 item 9)"

    /** Every method call in this class file, as `owner.name` (internal owner name). */
    fun calledMethods(classFile: ByteArray): Set<String> {
        val found = sortedSetOf<String>()
        val method = object : MethodVisitor(Opcodes.ASM9) {
            override fun visitMethodInsn(opcode: Int, owner: String, name: String, d: String, itf: Boolean) {
                found += "$owner.$name"
            }
        }
        ClassReader(classFile).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(a: Int, n: String, d: String, s: String?, ex: Array<String>?) = method
            },
            ClassReader.SKIP_FRAMES,
        )
        return found
    }

    /** Every class this class file refers to: supertypes, fields, methods, instructions, constants, signatures. */
    fun referencedTypes(classFile: ByteArray): Set<String> {
        val found = sortedSetOf<String>()

        fun type(t: Type) {
            when (t.sort) {
                Type.ARRAY -> {
                    type(t.elementType)
                }

                Type.OBJECT -> {
                    found += t.internalName
                }

                Type.METHOD -> {
                    t.argumentTypes.forEach(::type)
                    type(t.returnType)
                }
            }
        }

        fun desc(d: String?) {
            if (d != null) type(if (d.startsWith("(")) Type.getMethodType(d) else Type.getType(d))
        }

        fun internal(name: String?) {
            if (name != null) type(if (name.startsWith("[")) Type.getType(name) else Type.getObjectType(name))
        }

        fun signature(s: String?) {
            if (s == null) return
            SignatureReader(s).accept(
                object : SignatureVisitor(Opcodes.ASM9) {
                    override fun visitClassType(name: String) {
                        found += name
                    }
                },
            )
        }

        fun constant(c: Any?) {
            when (c) {
                is Type -> {
                    type(c)
                }

                is Handle -> {
                    internal(c.owner)
                    desc(c.desc)
                }

                is ConstantDynamic -> {
                    desc(c.descriptor)
                    constant(c.bootstrapMethod)
                    (0 until c.bootstrapMethodArgumentCount).forEach { constant(c.getBootstrapMethodArgument(it)) }
                }
            }
        }

        val method = object : MethodVisitor(Opcodes.ASM9) {
            override fun visitTypeInsn(opcode: Int, t: String) = internal(t)

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, d: String) {
                internal(owner)
                desc(d)
            }

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, d: String, itf: Boolean) {
                internal(owner)
                desc(d)
            }

            override fun visitInvokeDynamicInsn(name: String, d: String, bsm: Handle, vararg args: Any?) {
                desc(d)
                constant(bsm)
                args.forEach(::constant)
            }

            override fun visitLdcInsn(value: Any?) = constant(value)

            override fun visitMultiANewArrayInsn(d: String, dims: Int) = desc(d)

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, t: String?) = internal(t)

            override fun visitLocalVariable(n: String, d: String, s: String?, a: Label, b: Label, i: Int) {
                desc(d)
                signature(s)
            }
        }
        ClassReader(classFile).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(v: Int, a: Int, name: String, s: String?, superName: String?, itfs: Array<String>?) {
                    internal(superName)
                    itfs?.forEach(::internal)
                    signature(s)
                }

                override fun visitField(a: Int, n: String, d: String, s: String?, value: Any?): FieldVisitor? {
                    desc(d)
                    signature(s)
                    return null
                }

                override fun visitMethod(a: Int, n: String, d: String, s: String?, ex: Array<String>?): MethodVisitor {
                    desc(d)
                    signature(s)
                    ex?.forEach(::internal)
                    return method
                }
            },
            ClassReader.SKIP_FRAMES,
        )
        return found
    }

    /** The class's own internal name (to let a class mention itself). */
    fun nameOf(classFile: ByteArray): String = ClassReader(classFile).className

    /** Violations in [classFiles] (name for the report → bytes) of project [projectPath]; empty when clean. */
    fun check(projectPath: String, classFiles: Map<String, ByteArray>): List<String> =
        classFiles.toSortedMap().flatMap { (file, bytes) ->
            val self = nameOf(bytes)
            referencedTypes(bytes).flatMap { ref ->
                rules.filter { it.matches(ref) && projectPath !in it.allowedIn && !it.matches(self) }
                    .map { "$projectPath: $file refers to ${ref.replace('/', '.')}: ${it.why}" }
            } + calledMethods(bytes).filter { it in forbiddenCalls }
                .map { "$projectPath: $file calls ${it.replace('/', '.')}: $SEQUENCED" }
        }

    /** Reads every `.class` file under [dirs] and inside [jars]. */
    fun load(dirs: Collection<File>, jars: Collection<File>): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        dirs.filter { it.isDirectory }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach {
                out[it.relativeTo(dir).invariantSeparatorsPath] = it.readBytes()
            }
        }
        jars.filter { it.isFile }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.endsWith("module-info.class") }
                    .forEach { out["${jar.name}!${it.name}"] = zip.getInputStream(it).use { s -> s.readBytes() } }
            }
        }
        return out
    }
}

abstract class ForbiddenApisTask : DefaultTask() {
    @get:Input
    abstract val projectPath: Property<String>

    /** Pure-JVM modules: the main source set's class directories. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirs: ConfigurableFileCollection

    /** Android modules: the variant's project classes (AGP `ScopedArtifact.CLASSES`). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dirs: ListProperty<Directory>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun run() {
        val classes = ForbiddenApis.load(
            classDirs.files + dirs.getOrElse(emptyList()).map { it.asFile },
            jars.getOrElse(emptyList()).map { it.asFile },
        )
        val violations = ForbiddenApis.check(projectPath.get(), classes)
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText("${classes.size} classes checked\n" + violations.joinToString("") { "$it\n" })
        if (violations.isNotEmpty()) {
            throw GradleException("Forbidden APIs in compiled classes:\n" + violations.joinToString("\n") { " - $it" })
        }
    }
}

/**
 * Registers `checkForbiddenApis` (part of `check`): every main class of this module, per Android variant or the
 * JVM main source set, against [ForbiddenApis.rules].
 */
internal fun Project.registerForbiddenApis() {
    val modulePath = path
    val all = tasks.register("checkForbiddenApis") {
        group = "verification"
        description = "Fails if compiled main classes use a network API outside backup:net (ADR 0008/0011, D6) " +
            "or a Java 21 List method missing before API 35 (owner brief H4 item 9)."
    }
    pluginManager.withPlugin("base") { tasks.named("check") { dependsOn(all) } }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val main = extensions.getByType<SourceSetContainer>().getByName("main")
        val task = tasks.register<ForbiddenApisTask>("checkMainForbiddenApis") {
            projectPath.set(modulePath)
            classDirs.from(main.output.classesDirs)
            reportFile.set(layout.buildDirectory.file("reports/policy/main-forbidden-apis.txt"))
        }
        all.configure { dependsOn(task) }
    }
    listOf("com.android.application", "com.android.library").forEach { id ->
        pluginManager.withPlugin(id) {
            project.extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
                val name = variant.name.replaceFirstChar { it.uppercase() }
                val task = tasks.register<ForbiddenApisTask>("check${name}ForbiddenApis") {
                    projectPath.set(modulePath)
                    reportFile.set(layout.buildDirectory.file("reports/policy/${variant.name}-forbidden-apis.txt"))
                }
                variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
                    .use(task)
                    .toGet(ScopedArtifact.CLASSES, ForbiddenApisTask::jars, ForbiddenApisTask::dirs)
                all.configure { dependsOn(task) }
            }
        }
    }
}
