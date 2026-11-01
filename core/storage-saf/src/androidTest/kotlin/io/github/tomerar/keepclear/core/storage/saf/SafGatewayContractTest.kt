// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.saf

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.core.storage.StorageGatewayContract
import io.github.tomerar.keepclear.testing.usb.FakeUsbProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * tasks.md M12: the same contract suite every gateway passes on the JVM (M1), run on a device against
 * [SafGateway] over a provider that behaves like a removable drive (case-insensitive, renames on collision).
 * The suite is written for JUnit 5; this JUnit 4 adapter runs each of its test methods by name.
 */
@RunWith(Parameterized::class)
class SafGatewayContractTest(private val testName: String) {
    class OnFakeUsb : StorageGatewayContract() {
        public override fun newGateway(): Pair<StorageGateway, KPath> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            FakeUsbProvider.reset(File(context.filesDir, "usb-contract"))
            val tree = DocumentsContract.buildTreeDocumentUri(
                FakeUsbProvider.authority(context),
                FakeUsbProvider.ROOT_DOC,
            )
            return SafGateway(context.contentResolver) to KPath.Saf(tree.toString(), FakeUsbProvider.ROOT_DOC)
        }
    }

    @Test
    fun contract() {
        try {
            OnFakeUsb::class.java.getMethod(testName).invoke(OnFakeUsb())
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun tests(): List<String> = contractTests()

        fun contractTests(): List<String> = StorageGatewayContract::class.java.methods
            .filter { it.isAnnotationPresent(org.junit.jupiter.api.Test::class.java) }
            .map { it.name }
            .sorted()
    }
}

/** Guards the adapter itself: if reflection finds nothing, the suite above would pass vacuously. */
class SafGatewayContractCoverageTest {
    @Test
    fun theWholeSuiteRuns() {
        assertTrue(SafGatewayContractTest.contractTests().size >= 16)
    }
}
