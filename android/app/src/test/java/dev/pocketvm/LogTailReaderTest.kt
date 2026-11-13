package dev.pocketvm

import dev.pocketvm.diagnostics.LogTailReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [LogTailReader] 的增量读取语义。
 *
 * 这个类刻意只依赖 `java.io`，测试也只依赖 JUnit，所以本机可以绕过 Gradle 的
 * 长 classpath 直接 `JUnitCore` 跑（见 AGENTS.md 里 Windows 命令行限制那条）。
 *
 * 注意：返回值与 [dev.pocketvm.diagnostics.LogTail.read] 一致，**保留**行尾换行，
 * 所以断言里统一用 [tail] 去掉尾部的 `\n` 再比较。
 */
class LogTailReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun LogTailReader.tail(file: java.io.File, maxLines: Int = 800): String =
        read(file, maxLines).trimEnd('\n')

    @Test
    fun `first read only takes the tail window`() {
        val file = temp.newFile("run.log")
        val line = "x".repeat(99) + "\n"
        repeat(100) { file.appendText(line) }   // 10 KB
        val reader = LogTailReader(maxBytes = 1024)

        val text = reader.tail(file)

        assertTrue("返回内容不应超过窗口", text.length <= 1024)
        assertTrue("应包含最后一行", text.endsWith("x".repeat(99)))
        assertTrue("不应整份读进来", reader.totalBytesRead <= 1024)
    }

    @Test
    fun `append reads only the new bytes`() {
        val file = temp.newFile("run.log")
        file.appendText("line1\n")
        val reader = LogTailReader(maxBytes = 4096)
        assertEquals("line1", reader.tail(file))

        val afterFirst = reader.totalBytesRead
        file.appendText("line2\n")

        assertEquals("line1\nline2", reader.tail(file))
        assertEquals("只应读到新增的 6 字节", 6L, reader.totalBytesRead - afterFirst)
    }

    @Test
    fun `unchanged file costs no io`() {
        val file = temp.newFile("run.log")
        file.appendText("abc\n")
        val reader = LogTailReader()
        reader.read(file)

        val before = reader.totalBytesRead
        repeat(5) { reader.read(file) }

        assertEquals("内容没变就不该再读盘", before, reader.totalBytesRead)
    }

    @Test
    fun `truncated file starts over`() {
        val file = temp.newFile("run.log")
        file.appendText("aaaa\nbbbb\n")
        val reader = LogTailReader()
        assertTrue(reader.tail(file).contains("bbbb"))

        // 模拟日志轮转：同名文件被清空后重新写短内容。
        file.writeText("cc\n")

        assertEquals("cc", reader.tail(file))
    }

    @Test
    fun `switching file resets the offset`() {
        val first = temp.newFile("a.log").apply { writeText("aaa\n") }
        val second = temp.newFile("b.log").apply { writeText("bbb\n") }
        val reader = LogTailReader()

        assertEquals("aaa", reader.tail(first))
        assertEquals("bbb", reader.tail(second))
        assertEquals("切回来也要重新读对", "aaa", reader.tail(first))
    }

    @Test
    fun `utf8 split across appends is repaired`() {
        val file = temp.newFile("run.log")
        val bytes = "中\n".toByteArray(Charsets.UTF_8)   // e4 b8 ad 0a
        file.appendBytes(bytes.copyOfRange(0, 2))        // 先写半个字符
        val reader = LogTailReader()
        reader.read(file)                                // 此时只会解出替换符

        file.appendBytes(bytes.copyOfRange(2, bytes.size))

        assertEquals("补齐后应解码成完整汉字", "中", reader.tail(file))
    }

    @Test
    fun `respects max lines`() {
        val file = temp.newFile("run.log")
        (1..50).forEach { file.appendText("l$it\n") }
        val reader = LogTailReader()

        val text = reader.read(file, maxLines = 5)

        assertEquals(listOf("l46", "l47", "l48", "l49", "l50"), text.trimEnd('\n').lines())
    }

    @Test
    fun `trimmed buffer starts at a line boundary`() {
        val file = temp.newFile("run.log")
        repeat(50) { file.appendText("0123456789\n") }   // 每行 11 字节，共 550
        val reader = LogTailReader(maxBytes = 100)

        val lines = reader.tail(file).lines()

        assertTrue("第一行必须是完整行，不能是半截", lines.first().length == 10)
        assertTrue("所有行都应完整", lines.all { it.length == 10 })
    }

    @Test
    fun `missing file returns empty`() {
        val reader = LogTailReader()
        val missing = java.io.File(temp.root, "nope.log")

        assertEquals("", reader.read(missing))
    }
}
