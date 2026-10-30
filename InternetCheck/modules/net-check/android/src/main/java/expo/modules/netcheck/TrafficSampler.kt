package expo.modules.netcheck

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import java.io.File

/**
 * Снимок пассивного трафика устройства. Имена интерфейсов берутся из публичного
 * ConnectivityManager, а не из полного списка устройств ядра: так VPN и
 * произвольные виртуальные/дочерние устройства не попадают в сумму.
 */
class TrafficSampler(
    private val interfaceNames: () -> List<String> = { emptyList() },
    private val counterReader: (String, String) -> Long = { name, counter ->
        readCounterFile(name, counter)
    },
    private val totalsReader: () -> Pair<Long, Long> = {
        totalCounters()
    }
) {
    data class Sample(val rx: Long, val tx: Long, val source: String)
    data class NetworkCandidate(val interfaceName: String?, val hasInternet: Boolean, val isVpn: Boolean)

    constructor(connectivityManager: ConnectivityManager) : this(
        interfaceNames = { internetInterfaceNames(connectivityManager) }
    )

    fun sample(network: String): Sample {
        val names = interfaceNames().filter { it.isNotBlank() }.distinct().sorted()
        val counters = names.map { name ->
            name to (counterReader(name, "rx_bytes") to counterReader(name, "tx_bytes"))
        }
        val complete = names.isNotEmpty() && counters.all { it.second.first >= 0L && it.second.second >= 0L }
        if (complete) {
            val rx = counters.sumOf { it.second.first }
            val tx = counters.sumOf { it.second.second }
            return Sample(rx, tx, "sysfs-interfaces:${names.joinToString(",")};network=$network")
        }

        // Частичный sysfs нельзя складывать: это создаёт ложные пики при появлении
        // недоступного интерфейса. В fallback переключаемся целиком на device totals.
        val totals = totalsReader()
        return Sample(
            totals.first,
            totals.second,
            "device-total;reason=sysfs-incomplete;interfaces:${names.joinToString(",")};network=$network"
        )
    }

    companion object {
        fun selectInternetInterfaceNames(candidates: Iterable<NetworkCandidate>): List<String> =
            candidates.asSequence()
                .filter { it.hasInternet && !it.isVpn }
                .mapNotNull { it.interfaceName?.takeIf { name -> name.isNotBlank() } }
                .distinct()
                .sorted()
                .toList()

        /** Только INTERNET-сети без VPN; interfaceName — основной интерфейс сети. */
        fun internetInterfaceNames(cm: ConnectivityManager): List<String> {
            val candidates = ArrayList<NetworkCandidate>()
            val networks = try { cm.allNetworks } catch (_: Throwable) { emptyArray() }
            for (network in networks) {
                try {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    candidates += NetworkCandidate(
                        cm.getLinkProperties(network)?.interfaceName,
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    )
                } catch (_: Throwable) {
                    // A concurrently disappearing network is expected here.
                }
            }
            return selectInternetInterfaceNames(candidates)
        }

        private fun readCounterFile(name: String, counter: String): Long = try {
            File("/sys/class/net/$name/statistics/$counter").readText().trim().toLong()
        } catch (_: Throwable) {
            -1L
        }

        private fun totalCounters(): Pair<Long, Long> {
            val rx = try { TrafficStats.getTotalRxBytes() } catch (_: Throwable) { -1L }
            val tx = try { TrafficStats.getTotalTxBytes() } catch (_: Throwable) { -1L }
            return rx to tx
        }
    }
}
