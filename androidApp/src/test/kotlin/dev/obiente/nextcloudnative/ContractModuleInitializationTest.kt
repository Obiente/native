package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loads every packaged contract-module facade on the JVM and keeps the Android instrumented
 * initializer list exhaustive. This complements, and does not replace, the run on ART and ICU.
 */
class ContractModuleInitializationTest {
    @Test
    fun everyContractFacadeInitializesAndIsListedForAndroidRuntimeCoverage() {
        val facades = packagedFacades()
        assertTrue(facades.size >= 10, "The contract module facades were not found: $facades")
        facades.forEach { name -> Class.forName("$CONTRACT_PACKAGE.$name") }
        val instrumented = File(INSTRUMENTED_TEST).readText()
        assertEquals(emptyList(), facades.filterNot { name -> "\"$name\"" in instrumented })
    }

    private fun packagedFacades(): List<String> {
        val location = File(ContractAcquisitionRequest::class.java.protectionDomain.codeSource.location.toURI())
        val prefix = CONTRACT_PACKAGE.replace('.', '/') + "/"
        val entries = if (location.isDirectory) {
            location.walkTopDown().filter(File::isFile).map { it.relativeTo(location).invariantSeparatorsPath }.toList()
        } else {
            JarFile(location).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
        }
        return entries
            .filter { it.startsWith(prefix) && it.endsWith("Kt.class") && '$' !in it }
            .map { it.removePrefix(prefix).removeSuffix(".class") }
            .filter { '/' !in it }
            .sorted()
    }

    private companion object {
        const val CONTRACT_PACKAGE = "dev.obiente.nextcloudnative.contracts"
        const val INSTRUMENTED_TEST =
            "src/androidTest/kotlin/dev/obiente/nextcloudnative/AppOwnedOpenApiAndroidInstrumentedTest.kt"
    }
}
