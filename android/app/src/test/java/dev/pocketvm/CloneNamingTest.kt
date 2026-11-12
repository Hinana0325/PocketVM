package dev.pocketvm

import dev.pocketvm.storage.CloneNaming
import org.junit.Assert.assertEquals
import org.junit.Test

/** VM 复制命名：自动加「副本」并去重。 */
class CloneNamingTest {

    @Test
    fun `first clone gets plain suffix`() {
        assertEquals("Windows 11 副本", CloneNaming.uniqueCloneName("Windows 11", emptyList()))
    }

    @Test
    fun `repeated clones get increasing numbers`() {
        val existing = listOf("Windows 11", "Windows 11 副本", "Windows 11 副本 2")
        assertEquals("Windows 11 副本 3", CloneNaming.uniqueCloneName("Windows 11", existing))
    }

    @Test
    fun `gaps are filled from the smallest free number`() {
        val existing = listOf("Arch 副本", "Arch 副本 3")
        assertEquals("Arch 副本 2", CloneNaming.uniqueCloneName("Arch", existing))
    }

    @Test
    fun `blank name falls back to a generic base`() {
        assertEquals("虚拟机 副本", CloneNaming.uniqueCloneName("   ", emptyList()))
    }
}
