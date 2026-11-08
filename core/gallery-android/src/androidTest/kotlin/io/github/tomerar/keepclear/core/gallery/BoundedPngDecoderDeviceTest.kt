// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.core.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

@RunWith(AndroidJUnit4::class)
class BoundedPngDecoderDeviceTest {
    @Test
    fun completeAndIncompletePng(): Unit = runBlocking {
        val complete = png(height = 1)
        assertNotNull(BoundedPngDecoder.decode(ByteArrayInputStream(complete)))
        val partial = png(height = 2)
        assertTrue(BoundedPngDecoder.isSupportedPng(partial))
        assertNull(BoundedPngDecoder.decode(ByteArrayInputStream(partial)))
    }

    private fun png(height: Int): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(byteArrayOf(0, -1, 0, 0, -1, 0, -1, 0, -1)) }
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(2)
            writeInt(height)
            write(byteArrayOf(8, 6, 0, 0, 0))
        }
        chunk(output, "IHDR", header.toByteArray())
        chunk(output, "IDAT", compressed.toByteArray())
        chunk(output, "IEND", byteArrayOf())
        return bytes.toByteArray()
    }

    private fun chunk(output: DataOutputStream, name: String, data: ByteArray) {
        val type = name.toByteArray(Charsets.US_ASCII)
        output.writeInt(data.size)
        output.write(type)
        output.write(data)
        val crc = CRC32().apply {
            update(type)
            update(data)
        }
        output.writeInt(crc.value.toInt())
    }
}
