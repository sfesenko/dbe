package dbe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream

class McpServerTest {

    // ── EofAwareInputStream ─────────────────────────────────────────────────────

    @Test
    fun `EofAwareInputStream triggers onEof when read returns -1`() {
        var eofCount = 0
        val input = EofAwareInputStream(ByteArrayInputStream(ByteArray(0))) { eofCount++ }

        val readResult = input.read()

        assertEquals(-1, readResult)
        assertEquals(1, eofCount)
    }

    @Test
    fun `EofAwareInputStream triggers onEof when batch read returns -1`() {
        var eofCount = 0
        val input = EofAwareInputStream(ByteArrayInputStream(ByteArray(0))) { eofCount++ }

        val buffer = ByteArray(16)
        val readResult = input.read(buffer, 0, buffer.size)

        assertEquals(-1, readResult)
        assertEquals(1, eofCount)
    }

    @Test
    fun `EofAwareInputStream calls onEof only once`() {
        var eofCount = 0
        val input = EofAwareInputStream(ByteArrayInputStream(ByteArray(0))) { eofCount++ }

        input.read()
        input.read()
        input.read()

        assertEquals(1, eofCount)
    }

    @Test
    fun `EofAwareInputStream calls onEof only once for batch reads`() {
        var eofCount = 0
        val input = EofAwareInputStream(ByteArrayInputStream(ByteArray(0))) { eofCount++ }

        val buffer = ByteArray(16)
        input.read(buffer, 0, buffer.size)
        input.read(buffer, 0, buffer.size)

        assertEquals(1, eofCount)
    }

    @Test
    fun `EofAwareInputStream delegates single-byte reads`() {
        val data = byteArrayOf(65, 66, 67) // A, B, C
        var eofCount = 0
        val input = EofAwareInputStream(ByteArrayInputStream(data)) { eofCount++ }

        assertEquals(65, input.read())
        assertEquals(66, input.read())
        assertEquals(67, input.read())
        assertEquals(-1, input.read()) // EOF
        assertEquals(1, eofCount)
    }

    @Test
    fun `EofAwareInputStream delegates batch reads`() {
        val data = "Hello".toByteArray()
        var eofCount = 0
        val input = EofAwareInputStream(ByteArrayInputStream(data)) { eofCount++ }

        val buffer = ByteArray(10)
        val count = input.read(buffer, 0, buffer.size)

        assertEquals(5, count)
        assertEquals("Hello", String(buffer, 0, count))
        assertEquals(0, eofCount) // not EOF yet
    }

    @Test
    fun `EofAwareInputStream delegates close`() {
        val delegate = ByteArrayInputStream("test".toByteArray())
        val input = EofAwareInputStream(delegate) {}

        input.close()
        // After close, reading should throw or return -1 (ByteArrayInputStream behavior)
        // Just verify no exception from close itself
    }

    @Test
    fun `EofAwareInputStream does not trigger onEof for partial reads`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        var eofCount = 0
        val input = EofAwareInputStream(ByteArrayInputStream(data)) { eofCount++ }

        val buffer = ByteArray(3)
        val count = input.read(buffer, 0, 3)

        assertEquals(3, count)
        assertEquals(0, eofCount)
    }

    /** A delegate that deliberately skips the InputStream bounds contract. */
    private class NoValidationInputStream : InputStream() {
        override fun read(): Int = -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
    }

    @Test
    fun `EofAwareInputStream rejects negative offset`() {
        val input = EofAwareInputStream(NoValidationInputStream()) {}

        assertThrows<IndexOutOfBoundsException> { input.read(ByteArray(4), -1, 2) }
    }

    @Test
    fun `EofAwareInputStream rejects negative length`() {
        val input = EofAwareInputStream(NoValidationInputStream()) {}

        assertThrows<IndexOutOfBoundsException> { input.read(ByteArray(4), 0, -1) }
    }

    @Test
    fun `EofAwareInputStream rejects length beyond buffer`() {
        val input = EofAwareInputStream(NoValidationInputStream()) {}

        assertThrows<IndexOutOfBoundsException> { input.read(ByteArray(4), 2, 3) }
    }

    @Test
    fun `EofAwareInputStream triggers onEof after consuming all data then reading`() {
        val data = byteArrayOf(1, 2)
        var eofCount = 0
        val input = EofAwareInputStream(ByteArrayInputStream(data)) { eofCount++ }

        input.read() // 1
        input.read() // 2
        assertEquals(0, eofCount)

        input.read() // -1 → EOF
        assertEquals(1, eofCount)
    }
}
