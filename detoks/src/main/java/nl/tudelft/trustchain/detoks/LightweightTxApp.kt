package nl.tudelft.trustchain.detoks

import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import com.squareup.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private fun createCommunity(): OverlayConfiguration<TransactionEngine> {
        val randomWalk = RandomWalk.Factory(timeout = 3.0, peers = 20)
        return OverlayConfiguration(
            TransactionEngine.Factory(DetoksConfig.DETOKS_SERVICE_ID),
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

        // storage
        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val store = TrustChainSQLiteStore(Database(driver))

        val ipv8 = IPv8(endpoint, config, myPeer)
        ipv8.start()

        scope.launch {
            while (true) {
                val command = readLine()!!
                println("User said: $command")

                val overlay = ipv8.overlays.values.toList()[0]
                printPeersInfo(overlay)

                val community = ipv8.getOverlay<TransactionEngine>()!!

                if (command == "send") {
                    println("Sending single proposal...")
                    val blockBuilder = ProposalBlockBuilder(myPeer, store, "test1",
                                                            mapOf<String, Any>(),
                                                            myKey.pub().keyToBin())
                    if (community.getPeers().isNotEmpty()) {
                        community.sendTransaction(blockBuilder=blockBuilder,
                                                  community.getPeers()[0])

                    }
                    println("Proposal sent")
                } else if (command == "send-1000") {
                    println("Sending 1000 proposals...")
                    val blockBuilder = ProposalBlockBuilder(myPeer, store, "test1000",
                        mapOf<String, Any>(),
                        myKey.pub().keyToBin())
                    if (community.getPeers().isNotEmpty()) {
                        for (i in 1..1000) {
                            community.sendTransaction(blockBuilder=blockBuilder,
                                community.getPeers()[0])
                        }
                    }
                    println("1000 proposals sent")
                } else {
                    println("Unknown command: $command")
                }
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
