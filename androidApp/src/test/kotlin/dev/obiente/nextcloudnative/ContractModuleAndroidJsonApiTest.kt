package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import java.io.DataInputStream
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The contract module compiles against the JSON-java library, but on Android the framework's own
 * org.json classes are loaded first. Any member that only JSON-java has compiles and passes JVM
 * tests, then fails on a device with NoSuchMethodError. This test reads every org.json member the
 * packaged contract module references and requires each one to exist in the org.json classes of
 * the compileSdk android.jar, which Gradle passes in from the Android plugin's boot classpath.
 */
class ContractModuleAndroidJsonApiTest {
    @Test
    fun everyOrgJsonMemberTheContractModuleUsesExistsOnAndroid() {
        val platform = androidOrgJsonClasses()
        assertTrue(platform.containsKey("org/json/JSONObject"), "The boot classpath has no org.json classes.")
        val references = contractModuleClassFiles().flatMap { (name, bytes) ->
            ClassFile.parse(bytes).references
                .filter { reference -> reference.owner.startsWith("org/json/") }
                .map { reference -> name to reference }
        }
        assertTrue(references.size >= 20, "Too few org.json references were found: ${references.size}")
        val missing = references.filterNot { (_, reference) -> platform.resolves(reference) }
            .map { (user, reference) -> "$user -> ${reference.owner}.${reference.name}${reference.descriptor}" }
            .distinct()
            .sorted()
        assertEquals(emptyList<String>(), missing, "These org.json members do not exist in Android's org.json.")
    }

    @Test
    fun everyOrgJsonClassTheContractModuleNamesExistsOnAndroid() {
        val platform = androidOrgJsonClasses()
        val missing = contractModuleClassFiles().flatMap { (user, bytes) ->
            ClassFile.parse(bytes).classReferences
                .map { name -> name.trimStart('[').removePrefix("L").removeSuffix(";") }
                .filter { name -> name.startsWith("org/json/") && name !in platform }
                .map { name -> "$user -> $name" }
        }.distinct().sorted()
        assertEquals(emptyList<String>(), missing, "These org.json classes do not exist in Android's org.json.")
    }

    @Test
    fun aJsonJavaOnlyMemberIsReported() {
        val platform = androidOrgJsonClasses()
        val similar = ClassFile.Member(JSON_OBJECT, ClassFile.Kind.Method, "similar", "(Ljava/lang/Object;)Z")
        val keys = ClassFile.Member(JSON_OBJECT, ClassFile.Kind.Method, "keys", "()Ljava/util/Iterator;")
        assertEquals(false, platform.resolves(similar))
        assertEquals(true, platform.resolves(keys))
    }

    private fun androidOrgJsonClasses(): Map<String, ClassFile> {
        val bootClasspath = assertNotNull(
            System.getProperty(BOOT_CLASSPATH_PROPERTY),
            "Gradle must pass the Android boot classpath as $BOOT_CLASSPATH_PROPERTY.",
        )
        val androidJar = bootClasspath.split(File.pathSeparator).map(::File)
            .firstOrNull { it.name == "android.jar" }
        return JarFile(assertNotNull(androidJar, "No android.jar in $bootClasspath")).use { jar ->
            jar.entries().asSequence()
                .filter { it.name.startsWith("org/json/") && it.name.endsWith(".class") }
                .associate { entry ->
                    val parsed = ClassFile.parse(jar.getInputStream(entry).use { it.readBytes() })
                    parsed.name to parsed
                }
        }
    }

    private fun contractModuleClassFiles(): List<Pair<String, ByteArray>> {
        val location = File(ContractAcquisitionRequest::class.java.protectionDomain.codeSource.location.toURI())
        return if (location.isDirectory) {
            location.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                .map { it.relativeTo(location).invariantSeparatorsPath to it.readBytes() }
                .toList()
        } else {
            JarFile(location).use { jar ->
                jar.entries().asSequence().filter { it.name.endsWith(".class") }
                    .map { entry -> entry.name to jar.getInputStream(entry).use { it.readBytes() } }
                    .toList()
            }
        }
    }

    /**
     * A member resolves when it is declared by its owner or, except for constructors, by a
     * superclass. Superclasses outside org.json, such as java.lang.Object or Exception, are part of
     * the Java runtime on both platforms and are checked by reflection.
     */
    private fun Map<String, ClassFile>.resolves(member: ClassFile.Member): Boolean {
        var owner: String? = member.owner
        while (owner != null) {
            val platformClass = this[owner]
            if (platformClass == null) return owner.startsWith("java/") && runtimeDeclares(owner, member)
            if (platformClass.declares(member)) return true
            if (member.name == "<init>") return false
            owner = platformClass.superName
        }
        return false
    }

    private fun runtimeDeclares(owner: String, member: ClassFile.Member): Boolean {
        var type: Class<*>? = Class.forName(owner.replace('/', '.'))
        while (type != null) {
            val found = when (member.kind) {
                ClassFile.Kind.Field -> type.declaredFields.any { field ->
                    field.name == member.name && descriptorOf(field.type) == member.descriptor
                }
                ClassFile.Kind.Method -> type.declaredMethods.any { method ->
                    val parameters = method.parameterTypes.joinToString("") { descriptorOf(it) }
                    method.name == member.name && "($parameters)${descriptorOf(method.returnType)}" == member.descriptor
                }
            }
            if (found) return true
            type = type.superclass
        }
        return false
    }

    private fun descriptorOf(type: Class<*>): String = when {
        type.isArray -> "[" + descriptorOf(type.componentType)
        type == Void.TYPE -> "V"
        type == java.lang.Boolean.TYPE -> "Z"
        type == java.lang.Byte.TYPE -> "B"
        type == java.lang.Character.TYPE -> "C"
        type == java.lang.Short.TYPE -> "S"
        type == java.lang.Integer.TYPE -> "I"
        type == java.lang.Long.TYPE -> "J"
        type == java.lang.Float.TYPE -> "F"
        type == java.lang.Double.TYPE -> "D"
        else -> "L" + type.name.replace('.', '/') + ";"
    }

    private companion object {
        const val BOOT_CLASSPATH_PROPERTY = "native.androidBootClasspath"
        const val JSON_OBJECT = "org/json/JSONObject"
    }
}

/** The parts of a class file this check needs: its name, superclass, members, and member references. */
private class ClassFile(
    val name: String,
    val superName: String?,
    private val members: Set<Triple<Kind, String, String>>,
    val references: List<Member>,
    val classReferences: List<String>,
) {
    enum class Kind { Field, Method }

    data class Member(val owner: String, val kind: Kind, val name: String, val descriptor: String)

    fun declares(member: Member): Boolean = Triple(member.kind, member.name, member.descriptor) in members

    companion object {
        fun parse(bytes: ByteArray): ClassFile {
            val input = DataInputStream(bytes.inputStream())
            check(input.readInt() == 0xCAFEBABE.toInt()) { "Not a class file." }
            input.readUnsignedShort()
            input.readUnsignedShort()
            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val pairs = arrayOfNulls<IntArray>(count)
            val tags = IntArray(count)
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte()
                tags[index] = tag
                when (tag) {
                    1 -> utf8[index] = input.readUTF()
                    3, 4 -> input.readInt()
                    5, 6 -> {
                        input.readLong()
                        index += 1
                    }
                    7, 8, 16, 19, 20 -> pairs[index] = intArrayOf(input.readUnsignedShort())
                    9, 10, 11, 12, 17, 18 ->
                        pairs[index] = intArrayOf(input.readUnsignedShort(), input.readUnsignedShort())
                    15 -> {
                        input.readUnsignedByte()
                        input.readUnsignedShort()
                    }
                    else -> error("Unknown constant pool tag $tag")
                }
                index += 1
            }
            fun className(classIndex: Int): String = requireNotNull(utf8[requireNotNull(pairs[classIndex])[0]])
            input.readUnsignedShort()
            val name = className(input.readUnsignedShort())
            val superIndex = input.readUnsignedShort()
            val superName = if (superIndex == 0) null else className(superIndex)
            repeat(input.readUnsignedShort()) { input.readUnsignedShort() }
            val members = mutableSetOf<Triple<Kind, String, String>>()
            for (kind in listOf(Kind.Field, Kind.Method)) {
                repeat(input.readUnsignedShort()) {
                    input.readUnsignedShort()
                    val memberName = requireNotNull(utf8[input.readUnsignedShort()])
                    val descriptor = requireNotNull(utf8[input.readUnsignedShort()])
                    members += Triple(kind, memberName, descriptor)
                    repeat(input.readUnsignedShort()) {
                        input.readUnsignedShort()
                        input.skipBytes(input.readInt())
                    }
                }
            }
            val references = (1 until count).filter { tags[it] in 9..11 }.map { reference ->
                val (classIndex, nameAndType) = requireNotNull(pairs[reference]).let { it[0] to it[1] }
                val (nameIndex, typeIndex) = requireNotNull(pairs[nameAndType]).let { it[0] to it[1] }
                Member(
                    owner = className(classIndex),
                    kind = if (tags[reference] == 9) Kind.Field else Kind.Method,
                    name = requireNotNull(utf8[nameIndex]),
                    descriptor = requireNotNull(utf8[typeIndex]),
                )
            }
            val classReferences = (1 until count).filter { tags[it] == 7 }.map(::className)
            return ClassFile(name, superName, members, references, classReferences)
        }
    }
}
