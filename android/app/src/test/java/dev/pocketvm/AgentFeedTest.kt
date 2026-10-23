package dev.pocketvm

import dev.pocketvm.diagnostics.AgentFeed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The feed is consumed by external tooling (adb, scripts, agents), so its line
 * format is a contract: one JSON object per line, no nesting.
 */
class AgentFeedTest {

    @Test
    fun `emits one object carrying only the timestamp and event name`() {
        assertEquals(
            """{"t":1700000000000,"event":"session.start"}""",
            AgentFeed.line("session.start", emptyList(), 1_700_000_000_000L),
        )
    }

    @Test
    fun `numbers and booleans stay literals and null is explicit`() {
        assertEquals(
            """{"t":1,"event":"attempt.failed","run":"abc","exitCode":132,"ok":false,"signal":null}""",
            AgentFeed.line(
                "attempt.failed",
                listOf("run" to "abc", "exitCode" to 132, "ok" to false, "signal" to null),
                1L,
            ),
        )
    }

    @Test
    fun `collections flatten so consumers need no recursion`() {
        assertEquals(
            """{"t":1,"event":"validation","errors":"CPU_RANGE,MEM_TOO_SMALL"}""",
            AgentFeed.line(
                "validation",
                listOf("errors" to listOf("CPU_RANGE", "MEM_TOO_SMALL")),
                1L,
            ),
        )
    }

    /**
     * argv and Windows paths are full of backslashes, and QEMU's error lines have
     * quotes in them — an unescaped one would break the line for every reader.
     */
    @Test
    fun `quotes backslashes and control characters are escaped`() {
        assertEquals(
            """{"t":1,"event":"x","v":"say \"hi\"","p":"fs0:\\EFI\\BOOT","n":"a\nb","c":"a\u0001c"}""",
            AgentFeed.line(
                "x",
                listOf(
                    "v" to "say \"hi\"",
                    "p" to "fs0:\\EFI\\BOOT",
                    "n" to "a\nb",
                    "c" to "a\u0001c",
                ),
                1L,
            ),
        )
    }
}