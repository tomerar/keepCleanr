// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.storage.saf

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tomerar.keepclear.core.storage.KPath
import io.github.tomerar.keepclear.core.storage.StorageGateway
import io.github.tomerar.keepclear.core.storage.StorageGatewayContract
import io.github.tomerar.keepclear.testing.usb.FakeUsbProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * M12b: the same contract suite against a granted folder mounted at a real-looking path ([SafTreeMount]), which
 * is how the `play` flavor reaches WhatsApp's folder. Callers see ordinary paths; underneath it is SAF.
 */
@RunWith(Parameterized::class)
class SafTreeMountContractTest(private val testName: String) {
    class OnFakeUsb : StorageGatewayContract() {
        public override fun newGateway(): Pair<StorageGateway, KPath> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            FakeUsbProvider.reset(File(context.filesDir, "usb-mount"))
            val tree = DocumentsContract.buildTreeDocumentUri(
                FakeUsbProvider.authority(context),
                FakeUsbProvider.ROOT_DOC,
            )
            val point = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp"
            val mount = SafTreeMount(
                SafGateway(context.contentResolver),
                KPath.Saf(tree.toString(), FakeUsbProvider.ROOT_DOC),
                point,
            )
            return mount to KPath.Local(point)
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
        fun tests(): List<String> = SafGatewayContractTest.contractTests()
    }
}
