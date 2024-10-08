package korlibs.compression.deflate

import korlibs.io.lang.*
import korlibs.io.stream.*
import korlibs.memory.*
import kotlin.math.*


// @TODO: This interface is not good
internal interface DeflaterBitReader {
    val bigChunkSize: Int
    val readWithSize: Int
    val bitsavailable: Int
    val totalReadBits: Long
    fun ensureBits(bits: Int)
    suspend fun hasAvailable(): Boolean
    suspend fun getAvailable(): Long
    suspend fun abytes(count: Int): ByteArray
    fun su16LE(): Int
    fun sreadBit(): Boolean
    fun skipBits(bits: Int)
    fun peekBits(count: Int): Int
    fun readBits(count: Int): Int
    fun returnToBuffer(data: ByteArray, offset: Int, size: Int)
    suspend fun read(data: ByteArray, offset: Int, size: Int): Int
    suspend fun readBytesExact(count: Int): ByteArray
    suspend fun prepareBigChunkIfRequired()
}

// @TODO: This interface is not good
internal interface DeflaterAsyncOutputStream {
    suspend fun write(bytes: ByteArray, offset: Int, size: Int)
    suspend fun write8(value: Int)
    suspend fun write16LE(value: Int)
    suspend fun writeBytes(bytes: ByteArray)
}

internal fun BitReader.toDeflater(): DeflaterBitReader = object : DeflaterBitReader {
    override val bigChunkSize: Int get() = this@toDeflater.bigChunkSize
    override val readWithSize: Int get() = this@toDeflater.readWithSize
    override val bitsavailable: Int get() = this@toDeflater.bitsavailable
    override val totalReadBits: Long get() = this@toDeflater.totalReadBits
    override fun ensureBits(bits: Int) = this@toDeflater.ensureBits(bits)
    override suspend fun hasAvailable(): Boolean = this@toDeflater.hasAvailable()
    override suspend fun getAvailable(): Long  = this@toDeflater.getAvailable()
    override suspend fun abytes(count: Int): ByteArray = this@toDeflater.abytes(count)
    override fun su16LE(): Int = this@toDeflater.su16LE()
    override fun sreadBit(): Boolean = this@toDeflater.sreadBit()
    override fun skipBits(bits: Int)  = this@toDeflater.skipBits(bits)
    override fun peekBits(count: Int): Int  = this@toDeflater.peekBits(count)
    override fun readBits(count: Int): Int  = this@toDeflater.readBits(count)
    override fun returnToBuffer(data: ByteArray, offset: Int, size: Int) = this@toDeflater.returnToBuffer(data, offset, size)
    override suspend fun read(data: ByteArray, offset: Int, size: Int): Int = this@toDeflater.read(data, offset, size)
    override suspend fun readBytesExact(count: Int): ByteArray = this@toDeflater.readBytesExact(count)
    override suspend fun prepareBigChunkIfRequired() = this@toDeflater.prepareBigChunkIfRequired()
}

internal fun AsyncOutputStream.toDeflater(): DeflaterAsyncOutputStream = object : DeflaterAsyncOutputStream {
    override suspend fun write(bytes: ByteArray, offset: Int, size: Int) = this@toDeflater.write(bytes, offset, size)
    override suspend fun write8(value: Int) = this@toDeflater.write8(value)
    override suspend fun write16LE(value: Int) = this@toDeflater.write16LE(value)
    override suspend fun writeBytes(bytes: ByteArray) = this@toDeflater.writeBytes(bytes)
}

// Are there any missing totalReadBits incrementations in this class?
internal open class BitReader constructor(
    val s: AsyncInputStream,
    val bigChunkSize: Int = BIG_CHUNK_SIZE,
    val readWithSize: Int = READ_WHEN_LESS_THAN
) : AsyncInputStreamWithLength {
    override fun toString(): String = "BitReader($s, bigChunkSize=$bigChunkSize, readWithSize=$readWithSize)"

    companion object {
        const val BIG_CHUNK_SIZE = 8 * 1024 * 1024 // 8 MB
        //const val BIG_CHUNK_SIZE = 128 * 1024 // 128 KB
        //const val BIG_CHUNK_SIZE = 8 * 1024
        const val READ_WHEN_LESS_THAN = 32 * 1024

        suspend fun forInput(s: AsyncInputStream): BitReader {
            if (s is AsyncGetLengthStream && s.hasLength()) {
                val bigChunkSize = max(READ_WHEN_LESS_THAN, min(s.getLength(), BIG_CHUNK_SIZE.toLong()).toInt())
                val readWithSize = max(bigChunkSize / 2, READ_WHEN_LESS_THAN)
                //println("BitReader: bigChunkSize=$bigChunkSize, readWithSize=$readWithSize")
                return BitReader(s, bigChunkSize, readWithSize)
            }
            return BitReader(s)
        }
    }

    var bitdata = 0
    var bitsavailable = 0
    var totalReadBits: Long = 0

    inline fun discardBits(): BitReader {
        //if (bitsavailable > 0) println("discardBits: $bitsavailable")
        this.bitdata = 0
        this.totalReadBits += this.bitsavailable
        this.bitsavailable = 0
        return this
    }

    private val sbuffers = SimpleRingBuffer(ilog2(bigChunkSize.nextPowerOfTwo))
    private var sbuffersReadPos = 0.0
    private var sbuffersPos = 0.0
    val requirePrepare get() = sbuffers.availableRead < readWithSize

    suspend inline fun prepareBigChunkIfRequired() {
        if (requirePrepare) prepareBytesUpTo(bigChunkSize)
    }

    fun internalPeekBytes(out: ByteArray, offset: Int = 0, size: Int = out.size - offset): ByteArray {
        sbuffers.peek(out, offset, size)
        return out
    }

    fun returnToBuffer(data: ByteArray, offset: Int, size: Int) {
        sbuffers.write(data, offset, size)
        sbuffersPos += size
    }

    suspend fun prepareBytesUpTo(expectedBytes: Int) {
        while (sbuffers.availableRead < expectedBytes) {
            val readCount = min(expectedBytes, sbuffers.availableWriteBeforeWrap)
            if (readCount <= 0) break

            val transferred = s.read(sbuffers.internalBuffer, sbuffers.internalWritePos, readCount)
            if (transferred <= 0) break
            sbuffers.internalWriteSkip(transferred)

            sbuffersPos += transferred
        }
    }

    fun ensureBits(bitcount: Int) {
        while (this.bitsavailable < bitcount) {
            this.bitdata = this.bitdata or (_su8() shl this.bitsavailable)
            this.bitsavailable += 8
        }
    }

    fun peekBits(bitcount: Int): Int {
        return this.bitdata and ((1 shl bitcount) - 1)
    }


    fun skipBits(bitcount: Int) {
        this.bitdata = this.bitdata ushr bitcount
        this.bitsavailable -= bitcount
        this.totalReadBits += bitcount
    }

    fun readBits(bitcount: Int): Int {
        ensureBits(bitcount)
        val readed = peekBits(bitcount)
        skipBits(bitcount)
        return readed
    }

    fun sreadBit(): Boolean = readBits(1) != 0

    //var lastReadByte = 0

    private inline fun _su8(): Int {
        sbuffersReadPos++
        return sbuffers.readByte()
        //val byte = sbuffers.readByte()
        //lastReadByte = byte // @TODO: Check performance of this
        //return byte
    }

    fun sbytes_noalign(count: Int, out: ByteArray) {
        var offset = 0
        var count = count
        if (bitsavailable >= 8) {
            //println("EXPECTED: $lastReadByte, bitsavailable=$bitsavailable")
            if (bitsavailable % 8 != 0) {
                val bits = (bitsavailable % 8)
                skipBits(bits)
                //println("SKIP $bits")
            }
            //println("bitsavailable=$bitsavailable")
            while (bitsavailable >= 8) {
                val byte = readBits(8).toByte()
                //println("RECOVERED $byte")
                out[offset++] = byte
                count--
            }
        }
        discardBits()
        val readCount = sbuffers.read(out, offset, count)
        totalReadBits += count * 8

        if (readCount > 0) sbuffersReadPos += readCount
        //for (n in 0 until count) out[offset + n] = _su8().toByte()
    }

    fun sbytes(count: Int): ByteArray = sbytes(count, ByteArray(count))
    fun sbytes(count: Int, out: ByteArray): ByteArray {
        sbytes_noalign(count, out)
        return out
    }
    fun su8(): Int = discardBits()._su8()
    fun su16LE(): Int {
        sbytes_noalign(2, temp)
        return temp.getU16LE(0)
    }
    fun su32LE(): Int {
        sbytes_noalign(4, temp)
        return temp.getS32LE(0)
    }
    fun su32BE(): Int {
        sbytes_noalign(4, temp)
        return temp.getS32BE(0)
    }

    private val temp = ByteArray(4)
    suspend fun abytes(count: Int, out: ByteArray = ByteArray(count)): ByteArray {
        prepareBytesUpTo(count)
        return sbytes(count, out)
    }
    override suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        prepareBytesUpTo(len)
        val out = sbuffers.read(buffer, offset, len)
        sbuffersReadPos += out
        return out
    }

    override suspend fun close() {
        s.close()
    }

    suspend fun strz(): String = MemorySyncStreamToByteArray {
        discardBits()
        while (true) {
            prepareBigChunkIfRequired()
            val c = _su8()
            if (c == 0) break
            write8(c)
        }
    }.toString(ASCII)

    @Suppress("EXPERIMENTAL_API_USAGE")
    suspend fun copyTo(o: AsyncOutputStream) {
        while (true) {
            prepareBigChunkIfRequired()
            val read = sbuffers.availableReadBeforeWrap
            if (read <= 0) break
            sbuffersReadPos += read
            o.writeBytes(sbuffers.internalBuffer, sbuffers.internalReadPos, read)
            sbuffers.internalReadSkip(read)
        }
    }

    //suspend fun readAll(): ByteArray {
    //	val temp = ByteArray(sbuffers.availableRead)
    //	sbuffers.readBytes(temp, 0, sbuffers.availableRead)
    //	return temp + s.readAll()
    //}
//
    //suspend fun hasAvailable() = s.hasAvailable()
    //suspend fun getAvailable(): Long = s.getAvailable()
    //suspend fun readBytesExact(count: Int): ByteArray = abytes(count)

    override suspend fun getPosition(): Long = sbuffersReadPos.toLong()
    override suspend fun hasLength(): Boolean = (s as? AsyncGetLengthStream)?.hasLength() ?: false
    override suspend fun getLength(): Long = (s as? AsyncGetLengthStream)?.getLength() ?: error("Length not available on Stream")
}

private val Int.nextPowerOfTwo: Int get() {
    var v = this
    v--
    v = v or (v shr 1)
    v = v or (v shr 2)
    v = v or (v shr 4)
    v = v or (v shr 8)
    v = v or (v shr 16)
    v++
    return v
}

private fun ilog2(v: Int): Int = if (v == 0) (-1) else (31 - v.countLeadingZeroBits())

