package dev.pocketvm

import dev.pocketvm.core.model.PortForward
import dev.pocketvm.runtime.SshDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshDefaultsTest {

    @Test
    fun `host port comes from the forward that targets guest 22`() {
        val forwards = listOf(PortForward(hostPort = 3389, guestPort = 3389), PortForward(2222, 22))
        assertEquals(2222, SshDefaults.hostPort(forwards))
        assertNull(SshDefaults.hostPort(listOf(PortForward(3389, 3389))))
        assertNull(SshDefaults.hostPort(emptyList()))
    }

    @Test
    fun `validation rejects empty host user and out-of-range port`() {
        assertTrue(SshDefaults.validate("", 22, "u")!!.contains("主机"))
        assertTrue(SshDefaults.validate("127.0.0.1", 0, "u")!!.contains("端口"))
        assertTrue(SshDefaults.validate("127.0.0.1", 70000, "u")!!.contains("端口"))
        assertTrue(SshDefaults.validate("127.0.0.1", 22, " ")!!.contains("用户名"))
        assertNull(SshDefaults.validate("127.0.0.1", 22, "ubuntu"))
    }
}
