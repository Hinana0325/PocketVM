package dev.pocketvm

import dev.pocketvm.storage.runQemuImgProcess
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** qemu-img 子进程的输出收集与真实超时行为。 */
class QemuImgProcessTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `collects output after a normal exit`() {
        val process = ProcessBuilder(javaExecutable().absolutePath, "-version")
            .redirectErrorStream(true)
            .start()

        val (code, output) = runQemuImgProcess(process, timeoutSec = 30)

        assertEquals(0, code)
        assertTrue(output.isNotBlank())
    }

    @Test
    fun `timeout kills the process even while its stdout stays open`() {
        val source = temp.newFile("PvmSleep.java")
        source.writeText(
            "class PvmSleep { public static void main(String[] args) " +
                "throws Exception { Thread.sleep(10000L); } }",
        )
        val process = ProcessBuilder(javaExecutable().absolutePath, source.absolutePath)
            .redirectErrorStream(true)
            .start()
        val startedAt = System.nanoTime()

        val (code, output) = runQemuImgProcess(
            process,
            timeoutSec = 1,
            outputGraceMs = 250,
        )

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertEquals(-1, code)
        assertTrue(output, output.contains("超时"))
        assertTrue("超时等待了 ${elapsedMs}ms", elapsedMs < 5_000)
        assertFalse(process.isAlive)
    }

    private fun javaExecutable(): File {
        val name = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"
        return File(System.getProperty("java.home"), "bin/$name")
    }
}
