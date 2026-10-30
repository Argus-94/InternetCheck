package expo.modules.netcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficSamplerTest {
    @Test fun incompleteSysfsUsesBothDeviceTotals() {
        val sampler = TrafficSampler(
            interfaceNames = { listOf("wlan0", "rmnet0") },
            counterReader = { name, counter ->
                if (name == "rmnet0" && counter == "tx_bytes") -1L else 100L
            },
            totalsReader = { 900L to 800L }
        )

        val sample = sampler.sample("Network-1;transport=wifi")

        assertEquals(900L, sample.rx)
        assertEquals(800L, sample.tx)
        assertTrue(sample.source.startsWith("device-total;reason=sysfs-incomplete"))
    }

    @Test fun changingInterfaceListChangesSourceAndSumsOnlySelectedInterfaces() {
        var names = listOf("wlan0")
        val sampler = TrafficSampler(
            interfaceNames = { names },
            counterReader = { name, counter -> if (counter == "rx_bytes") 10L else 20L },
            totalsReader = { 999L to 999L }
        )

        val first = sampler.sample("Network-1;transport=wifi")
        names = listOf("rmnet0")
        val second = sampler.sample("Network-2;transport=cell")

        assertEquals(10L, first.rx)
        assertEquals(20L, first.tx)
        assertTrue(first.source.contains("wlan0"))
        assertTrue(second.source.contains("rmnet0"))
        assertTrue(first.source != second.source)
    }

    @Test fun vpnAndNonInternetCandidatesAreExcluded() {
        assertEquals(
            listOf("wlan0"),
            TrafficSampler.selectInternetInterfaceNames(
                listOf(
                    TrafficSampler.NetworkCandidate("tun0", true, true),
                    TrafficSampler.NetworkCandidate("rmnet0", false, false),
                    TrafficSampler.NetworkCandidate("wlan0", true, false),
                    TrafficSampler.NetworkCandidate("wlan0", true, false)
                )
            )
        )
    }
}
