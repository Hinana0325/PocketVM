package dev.pocketvm

import dev.pocketvm.runtime.liveSnapshotError
import dev.pocketvm.runtime.withGuestResume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 运行时快照命令的错误传播与恢复运行契约。 */
class LiveSnapshotCommandTest {

    @Test
    fun `qmp error is surfaced instead of becoming a success`() {
        assertEquals("savevm is unavailable", liveSnapshotError("savevm is unavailable", ""))
    }

    @Test
    fun `monitor error text is surfaced`() {
        assertEquals("Error while writing VM state", liveSnapshotError(null, "Error while writing VM state"))
        assertNull(liveSnapshotError(null, ""))
    }

    @Test
    fun `guest resumes after a successful load`() {
        var resumes = 0
        val result = withGuestResume({ resumes += 1 }) { "loaded" }
        assertEquals("loaded", result)
        assertEquals(1, resumes)
    }

    @Test
    fun `guest resume is attempted when load fails and does not mask that failure`() {
        var resumes = 0
        val failure = assertThrows(IllegalStateException::class.java) {
            withGuestResume({
                resumes += 1
                error("cont failed")
            }) {
                error("load failed")
            }
        }
        assertEquals("load failed", failure.message)
        assertEquals(1, resumes)
    }
}
