package org.crea.reader.sync

import java.util.zip.Inflater

/** Random access to a file that lives somewhere else (for example Drive, through HTTP range requests). */
interface RangeSource {
    val size: Long

    /** Bytes `start..endInclusive`; shorter only if the range passes the end of the file. */
    suspend fun read(start: Long, endInclusive: Long): ByteArray
}

/**
 * Reads single entries out of a ZIP (an EPUB is one) without fetching the whole file: the end of
 * the archive gives the central directory, and each wanted entry is one small range request.
 * ZIP64 archives and entries over [MAX_ENTRY_BYTES] are not supported and simply yield null.
 */
class RemoteZip private constructor(
    private val source: RangeSource,
    private val entries: Map<String, Entry>,
) {
    class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val nameLength: Int,
        val extraLength: Int,
    )

    fun has(name: String): Boolean = name in entries

    fun names(): Collection<String> = entries.keys

    suspend fun read(name: String): ByteArray? {
        val entry = entries[name] ?: return null
        if (entry.compressedSize > MAX_ENTRY_BYTES || entry.uncompressedSize > MAX_ENTRY_BYTES) return null
        // One request for the local header and the data; the local extra field may be longer than the
        // central one, so ask for a little more than the central directory suggests.
        val start = entry.localHeaderOffset
        val end = minOf(
            source.size - 1,
            start + LOCAL_HEADER_SIZE + entry.nameLength + entry.extraLength + EXTRA_SLACK + entry.compressedSize
        )
        val chunk = source.read(start, end)
        if (chunk.size < LOCAL_HEADER_SIZE || u32(chunk, 0) != LOCAL_SIGNATURE) return null
        val dataStart = LOCAL_HEADER_SIZE + u16(chunk, 26) + u16(chunk, 28)
        val dataEnd = dataStart + entry.compressedSize.toInt()
        if (dataEnd > chunk.size) return null
        val raw = chunk.copyOfRange(dataStart, dataEnd)
        return when (entry.method) {
            METHOD_STORED -> raw
            METHOD_DEFLATED -> inflate(raw, entry.uncompressedSize.toInt())
            else -> null
        }
    }

    private fun inflate(raw: ByteArray, expected: Int): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(raw)
            val out = ByteArray(expected)
            var filled = 0
            while (filled < expected && !inflater.finished()) {
                val n = inflater.inflate(out, filled, expected - filled)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                filled += n
            }
            if (filled == expected) out else null
        } catch (_: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    companion object {
        const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
        private const val TAIL_BYTES = 64L * 1024
        private const val MAX_DIRECTORY_BYTES = 4L * 1024 * 1024
        private const val EXTRA_SLACK = 512
        private const val LOCAL_HEADER_SIZE = 30
        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val END_SIGNATURE = 0x06054b50L
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8

        /** Opens the archive's index, or returns null if it is not a ZIP this reader understands. */
        suspend fun open(source: RangeSource): RemoteZip? {
            if (source.size < 22) return null
            val tailStart = maxOf(0L, source.size - TAIL_BYTES)
            val tail = source.read(tailStart, source.size - 1)
            val end = (tail.size - 22 downTo 0).firstOrNull { u32(tail, it) == END_SIGNATURE } ?: return null

            val count = u16(tail, end + 10)
            val directorySize = u32(tail, end + 12)
            val directoryOffset = u32(tail, end + 16)
            if (count == 0xFFFF || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL) return null
            if (directorySize > MAX_DIRECTORY_BYTES || directoryOffset + directorySize > source.size) return null

            val directory = if (directoryOffset >= tailStart) {
                val from = (directoryOffset - tailStart).toInt()
                tail.copyOfRange(from, minOf(tail.size, from + directorySize.toInt()))
            } else {
                source.read(directoryOffset, directoryOffset + directorySize - 1)
            }

            val entries = LinkedHashMap<String, Entry>()
            var position = 0
            while (position + 46 <= directory.size && u32(directory, position) == CENTRAL_SIGNATURE) {
                val nameLength = u16(directory, position + 28)
                val extraLength = u16(directory, position + 30)
                val commentLength = u16(directory, position + 32)
                if (position + 46 + nameLength > directory.size) break
                val name = String(directory, position + 46, nameLength, Charsets.UTF_8)
                entries[name] = Entry(
                    name = name,
                    method = u16(directory, position + 10),
                    compressedSize = u32(directory, position + 20),
                    uncompressedSize = u32(directory, position + 24),
                    localHeaderOffset = u32(directory, position + 42),
                    nameLength = nameLength,
                    extraLength = extraLength,
                )
                position += 46 + nameLength + extraLength + commentLength
            }
            return if (entries.isEmpty()) null else RemoteZip(source, entries)
        }

        private fun u16(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

        private fun u32(bytes: ByteArray, at: Int): Long =
            (u16(bytes, at).toLong()) or (u16(bytes, at + 2).toLong() shl 16)
    }
}
