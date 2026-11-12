package dev.pocketvm

import dev.pocketvm.runtime.AppleCommandBuilder
import dev.pocketvm.runtime.AppleLaunchRequest
import dev.pocketvm.runtime.AppleRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppleCommandBuilderTest {

    private fun request(
        vncPort: Int = 5901,
        cores: Int = 4,
        memoryMb: Int = 3072,
        extraArgs: List<String> = emptyList(),
    ) = AppleLaunchRequest(
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

    private fun args(
        vncPort: Int = 5901,
        cores: Int = 4,
        memoryMb: Int = 3072,
        extraArgs: List<String> = emptyList(),
    ): List<String> = AppleCommandBuilder().build(
        request(vncPort, cores, memoryMb, extraArgs),
    )

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

    @Test
    fun `normal boot keeps the upstream command byte for byte`() {
        val machine = args()[args().indexOf("-machine") + 1]
        assertEquals("t8030", machine.substringBefore(','))
        assertFalse(machine.contains("boot-mode"))
        assertFalse(machine.contains("usb-conn"))
        assertFalse(args().contains("-initrd"))
        assertTrue(machine.endsWith(",kaslr-off=true"))
    }

    @Test
    fun `restore mode adds initrd boot-mode and USBTCP redirection`() {
        val a = AppleCommandBuilder().build(
            request().copy(
                restoreRamdisk = "/data/apple/038-44135-124.dmg",
                bootMode = "enter_recovery",
                usbConnType = "ipv4",
                usbConnAddr = "127.0.0.1",
                usbConnPort = 8030,
            ),
        )
        val machine = a[a.indexOf("-machine") + 1]
        assertTrue(machine.contains(",boot-mode=enter_recovery"))
        assertTrue(machine.contains(",usb-conn-type=ipv4"))
        assertTrue(machine.contains(",usb-conn-addr=127.0.0.1"))
        assertTrue(machine.contains(",usb-conn-port=8030"))
        assertTrue(machine.endsWith(",kaslr-off=true"))
        assertEquals("/data/apple/038-44135-124.dmg", a[a.indexOf("-initrd") + 1])
    }

    @Test
    fun `illegal boot mode and machine values are rejected`() {
        try {
            AppleCommandBuilder().build(request().copy(bootMode = "auto,sep-fw=/evil"))
            fail("boot-mode 白名单没生效")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("boot-mode"))
        }
        try {
            AppleCommandBuilder().build(request().copy(usbConnAddr = "127.0.0.1\nsep-fw=x"))
            fail("机型参数没有过滤换行")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("机型参数"))
        }
    }

    @Test
    fun `restore usb flag parses address and port and rejects bad values`() {
        assertEquals(
            "192.168.31.5" to 8030,
            AppleRestore.parseUsb("${AppleRestore.USB_PREFIX}192.168.31.5:8030"),
        )
        assertEquals(
            "127.0.0.1" to 1,
            AppleRestore.parseUsb("${AppleRestore.USB_PREFIX}127.0.0.1:1"),
        )
        listOf("127.0.0.1", "127.0.0.1:0", "127.0.0.1:70000", ":8030", "127.0.0.1:x")
            .forEach { raw ->
                assertEquals(
                    "应该拒绝 $raw",
                    null,
                    AppleRestore.parseUsb(AppleRestore.USB_PREFIX + raw),
                )
            }
    }
}
