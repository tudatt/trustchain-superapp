package nl.tudelft.trustchain.detoks

import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.*
import nl.tudelft.ipv8.attestation.trustchain.ProposalBlockBuilder
import nl.tudelft.ipv8.attestation.trustchain.store.TrustChainSQLiteStore
import nl.tudelft.ipv8.keyvault.JavaCryptoProvider
import nl.tudelft.ipv8.messaging.EndpointAggregator
import nl.tudelft.ipv8.messaging.udp.UdpEndpoint
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomWalk
import nl.tudelft.ipv8.sqldelight.Database
import java.net.InetAddress
import java.util.*
import kotlin.math.roundToInt

class DemoTransactionApp {
    private val scope = CoroutineScope(Dispatchers.Default)

    fun run() {
        startIpv8()
    }

    private fun createCommunity(): OverlayConfiguration<TransactionEngineImpl> {
        val randomWalk = RandomWalk.Factory(timeout = 3.0, peers = 20)
        return OverlayConfiguration(
            TransactionEngineImpl.Factory("c86a7db45eb3563ae047639817baec4db2bc7c25"),
            listOf(randomWalk)
        )
    }

    private fun startIpv8() {
        val myKey = JavaCryptoProvider.generateKey()
        val myPeer = Peer(myKey)
        val udpEndpoint = UdpEndpoint(8080, InetAddress.getByName("0.0.0.0"))
        val endpoint = EndpointAggregator(udpEndpoint, null)

        val config = IPv8Configuration(overlays = listOf(
            createCommunity()
        ), walkerInterval = 1.0)



        val ipv8 = IPv8(endpoint, config, myPeer)
        ipv8.start()

        val community = ipv8.getOverlay<TransactionEngineImpl>()!!
        val txBenchmark = TransactionEngineBenchmark(community, myPeer)
        val numberOfTransactions = 1
        scope.launch {
            while (true) {
                val overlay = ipv8.overlays.values.toList()[0]
                printPeersInfo(overlay)
                println("===")
                delay(5000)


                // unencrypted Basic block creation with the same content and the same addresses
                txBenchmark.unencryptedRandomSendIPv8(myKey, null, community.getPeers()[0])

                // unencrypted Basic block creation with the random content and random addresses
                txBenchmark.unencryptedRandomSendIPv8(myKey, null, community.getPeers()[0])

                // encrypted random blocks
                txBenchmark.encryptedRandomSendIPv8(myKey, null, community.getPeers()[0])
            }
        }

        while (ipv8.isStarted()) {
            Thread.sleep(1000)
        }
    }

    private fun printPeersInfo(overlay: Overlay) {
        val peers = overlay.getPeers()
        println(overlay::class.simpleName + ": ${peers.size} peers")
        for (peer in peers) {
            val avgPing = peer.getAveragePing()
            val lastRequest = peer.lastRequest
            val lastResponse = peer.lastResponse

            val lastRequestStr = if (lastRequest != null)
                "" + ((Date().time - lastRequest.time) / 1000.0).roundToInt() + " s" else "?"

            val lastResponseStr = if (lastResponse != null)
                "" + ((Date().time - lastResponse.time) / 1000.0).roundToInt() + " s" else "?"

            val avgPingStr = if (!avgPing.isNaN()) "" + (avgPing * 1000).roundToInt() + " ms" else "? ms"
            println("${peer.mid} (S: ${lastRequestStr}, R: ${lastResponseStr}, ${avgPingStr})")
        }
    }
}

fun main() {
    val app = DemoTransactionApp()
    app.run()
}
