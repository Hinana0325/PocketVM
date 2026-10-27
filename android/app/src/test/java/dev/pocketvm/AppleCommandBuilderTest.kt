package dev.pocketvm

import dev.pocketvm.runtime.AppleCommandBuilder
import dev.pocketvm.runtime.AppleLaunchRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleCommandBuilderTest {

    private fun args(
        vncPort: Int = 5901,
        cores: Int = 4,
        memoryMb: Int = 3072,
        extraArgs: List<String> = emptyList(),
    ): List<String> {
        val request = AppleLaunchRequest(
            executable = "/data/app/lib/libqemu-inferno-system-aarch64.so",
            assetDir = "/data/apple",
            cpuCores = cores,
            memoryMb = memoryMb,
            vncPort = vncPort,
            qmpSocketPath = "/data/run/vm.sock",
            dataDir = "/data/share-ios",
            vmName = "iPhone 11",
            extraArgs = extraArgs,
        )
        return AppleCommandBuilder().build(request)
    }

    @Test
    fun `t8030 machine carries every firmware path`() {
        val machine = args()[args().indexOf("-machine") + 1]
        assertTrue(machine.startsWith("t8030,"))
        assertTrue(machine.contains("trustcache=/data/apple/038-44135-124.dmg.trustcache"))
        assertTrue(machine.contains("ticket=/data/apple/root_ticket.der"))
        assertTrue(machine.contains("sep-fw=/data/apple/sep-firmware.n104.RELEASE.new.img4"))
        assertTrue(machine.contains("sep-rom=/data/apple/AppleSEPROM-Cebu-B1"))
        assertTrue(machine.contains("kaslr-off=true"))
    }

    @Test
    fun `kernel dtb and boot args are separate argv entries`() {
        val a = args()
        assertEquals("/data/apple/kernelcache.research.iphone12b", a[a.indexOf("-kernel") + 1])
        assertEquals("/data/apple/DeviceTree.n104ap.im4p", a[a.indexOf("-dtb") + 1])
        val append = a[a.indexOf("-append") + 1]
        assertTrue(append.contains("launchd_unsecure_cache=1"))
        assertTrue(append.contains("wdt=-1"))
    }

    @Test
    fun `all seven nvme namespaces are present and panic log keeps nstype 8`() {
        val joined = args().joinToString(" ")
        for (nsid in 1..7) {
            assertTrue("缺少 nsid=$nsid", joined.contains("nsid=$nsid"))
        }
        assertTrue(joined.contains("nsid=7,nstype=8"))
        assertTrue(joined.contains("apple-nvram"))
        assertTrue(joined.contains("sep_nvram"))
        assertTrue(joined.contains("sep_ssc"))
    }

    @Test
    fun `vnc takes display number not port`() {
        val a = args(vncPort = 5903)
        assertEquals("127.0.0.1:3", a[a.indexOf("-vnc") + 1])
    }

    @Test
    fun `qmp socket and serial log are wired`() {
        val a = args()
        assertEquals("unix:/data/run/vm.sock,server=on,wait=off", a[a.indexOf("-qmp") + 1])
        assertEquals("mon:stdio", a[a.indexOf("-serial") + 1])
        assertEquals("/data/share-ios", a[a.indexOf("-L") + 1])
    }

    @Test
    fun `cpu and memory are clamped and extra args appended last`() {
        val a = args(cores = 32, memoryMb = 256, extraArgs = listOf("-d", "int"))
        assertEquals("8", a[a.indexOf("-smp") + 1])
        assertEquals("1024", a[a.indexOf("-m") + 1])
        assertEquals(listOf("-d", "int"), a.takeLast(2))
    }
}
