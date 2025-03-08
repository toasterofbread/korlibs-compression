package kotlibs.compression.deflate

import assertk.assertThat
import assertk.assertions.isEqualTo
import korlibs.compression.deflate.DeflaterNative
import korlibs.compression.deflate.IDeflater
import korlibs.io.compression.CompressionMethod
import korlibs.io.compression.compress
import korlibs.io.compression.deflate.GZIP
import korlibs.io.compression.lzma.Lzma
import korlibs.io.compression.lzo.LZO
import korlibs.io.compression.uncompress
import korlibs.io.stream.MemorySyncStream
import korlibs.io.stream.openSync
import korlibs.memory.ByteArrayBuilder
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test

@ExperimentalStdlibApi
class CompressionMethodReturnSizeTest {
    private val testBytes: ByteArray = "Hello World!".repeat(200).encodeToByteArray()

    @Test
    fun deflaterNative_uncompress_returnedSizeAccurate() = runTest {
        testReturnSize(DeflaterNative(15))
    }

    @Test
    fun lzma_uncompress_returnedSizeAccurate() = runTest {
        testReturnSize(Lzma)
    }

    private fun testReturnSize(compressionMethod: CompressionMethod) {
        // GIVEN
        val compressed: ByteArray = compressionMethod.compress(testBytes)
        val compressedExtended: ByteArray = compressed + Random.nextBytes(100)

        val buffer: ByteArrayBuilder = ByteArrayBuilder(compressedExtended.size * 2)

        // WHEN
        val size: Long =
            compressionMethod.uncompress(compressedExtended.openSync(), MemorySyncStream(buffer))
        val uncompressed: ByteArray = buffer.toByteArray()

        // THEN
        assertThat(size).isEqualTo(compressed.size.toLong())
        assertThat(uncompressed).isEqualTo(testBytes)
    }
}
