package nl.tudelft.trustchain.detoks

import android.content.Context
import android.widget.TextView
import com.squareup.sqldelight.android.AndroidSqliteDriver
import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Overlay
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.attestation.trustchain.BlockBuilder
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.attestation.trustchain.UNKNOWN_SEQ
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockBroadcastPayload
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockPayload
import nl.tudelft.ipv8.attestation.trustchain.store.TrustChainSQLiteStore
import nl.tudelft.ipv8.keyvault.PrivateKey
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.sqldelight.Database
import java.util.*
import kotlin.collections.ArrayList
import com.github.mikephil.charting.data.Entry
import com.google.common.primitives.Bytes
import kotlinx.android.synthetic.main.test_fragment_layout.*
import nl.tudelft.ipv8.android.keyvault.AndroidCryptoProvider
import nl.tudelft.ipv8.attestation.trustchain.*
import nl.tudelft.ipv8.util.toHex
import kotlin.collections.HashMap

open class TransactionEngine (override val serviceId: String): Community() {

    fun sendTransaction(block: TrustChainBlock, peer: Peer, encrypt: Boolean = false, msgID: Int, myPeer:Peer) {
        println("sending block...")
        sendBlockToRecipient(peer, block, encrypt,msgID,myPeer)
    }

    fun addReceiver(onMessageId: Int, receiver: (Packet) -> Unit) {
        messageHandlers[msgIdFixer(onMessageId)] = receiver
    }

    private fun sendBlockToRecipient(peer: Peer, block: TrustChainBlock, encrypt: Boolean, msgID: Int, myPeer:Peer) {
        val payload = HalfBlockPayload.fromHalfBlock(block)

        val data = if (encrypt) {
            serializePacket(msgID, payload, false, encrypt = true, recipient = peer, peer = myPeer)
        } else {
            serializePacket(msgID, payload, false, peer = myPeer)
        }

        send(peer, data)
    }

    override fun onPacket(packet: Packet) {
        val sourceAddress = packet.source
        val data = packet.data

        val probablePeer = network.getVerifiedByAddress(sourceAddress)
        if (probablePeer != null) {
            probablePeer.lastResponse = Date()
        }

        val packetPrefix = data.copyOfRange(0, prefix.size)
        if (!packetPrefix.contentEquals(prefix)) {
            // logger.debug("prefix not matching")
            return
        }

        val msgId = data[prefix.size].toUByte().toInt()

        val handler = messageHandlers[msgId]

        if (handler != null) {
            try {
                handler(packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            println("Received unknown message $msgId from $sourceAddress")
        }
    }

    private fun msgIdFixer(msgid: Int): Int{
        @Suppress("DEPRECATION")
        return msgid.toChar().toByte().toInt()
    }

    class Factory(private val serviceId: String) : Overlay.Factory<TransactionEngine>(TransactionEngine::class.java) {
        override fun create(): TransactionEngine {
            return TransactionEngine(serviceId)
        }
    }
}

class SimpleBlockBuilder(
    myPeer : Peer,
    database: TrustChainSQLiteStore,
    private val blockType: String,
    private val transaction: ByteArray,
    private val publicKey: ByteArray
) : BlockBuilder(myPeer, database){
    override fun update(builder: TrustChainBlock.Builder) {
        builder.type = blockType
        builder.rawTransaction = transaction
        builder.linkPublicKey = publicKey
        builder.linkSequenceNumber = UNKNOWN_SEQ
    }

}

open class TransactionEngineBenchmark(
    private val txEngineUnderTest: TransactionEngine,
    private val myPeer: Peer
){
    object MessageId {
        const val BLOCK_UNENCRYPTED = 35007
        const val BLOCK_ENCRYPTED = 350072
        const val BLOCK_ENCRYPTED_ECHO = 3500721
    }


    private val incomingBlockEchos = mutableListOf<Long>()
    var currentBenchmarkIdentifier : String = ""
    var benchmarkStartTime : Long = 0
    private val receivedAgreements : ArrayList<Pair<TrustChainBlock, Float>> = ArrayList()
    var highestReceivedBenchmarkCounter : Int = 0
    var benchmarkCounter : Int = 0
    val trustchainIPv8graphData : ArrayList<Entry> = ArrayList()
    val trustchainSendTimeHashmap : HashMap<Int, Long> = HashMap()
    var latestReceivedAgreement : Long = 0


    init{
        txEngineUnderTest.addReceiver(MessageId.BLOCK_ENCRYPTED, ::onEncryptedRandomIPv8Packet)
        txEngineUnderTest.addReceiver(MessageId.BLOCK_ENCRYPTED_ECHO, ::onEncryptedEchoPacket)
    }
    // ================================== BLOCK CREATION METHODS ===================================
    /**
     * Benchmarks unencrypted block creation with the same content and same addresses.
     * @param blocksPerPoint how many blocks will correspond to one point in the final graph
     * @param limit either the time limit (in milliseconds) or the total number of blocks for the
     * benchmark to run (based on [benchmarkByTime])
     * @param benchmarkByTime whether or not the benchmark will run for a specific time or for a
     * specific number of blocks (both specified by [limit])
     * @return a [BenchmarkResult] instance
     */
    fun unencryptedBasicSameContent(blocksPerPoint: Int,
                                    limit: Int,
                                    benchmarkByTime: Boolean): BenchmarkResult {
        val graphPoints: ArrayList<Entry> = ArrayList()
        val blockType = "benchmark"
        val message: ByteArray = "benchmarkMessage".toByteArray()
        val senderPublicKey: ByteArray = "fakeKey".toByteArray()
        val receiverPublicKey: ByteArray = "fakeReceiverKey".toByteArray()
        val startTime = System.nanoTime()
        var packetsSent: Int = limit
        val payloadBandwidth: Double

        if (benchmarkByTime) {
            var counter = 0
            var previous: Long = System.nanoTime()
            while (System.nanoTime() < (startTime) + limit.toLong() * 1000000) {
                BasicBlock(blockType, message, senderPublicKey, receiverPublicKey)
                counter++
                if (counter % blocksPerPoint == 0) {
                    val x = counter.toFloat()
                    val y = (System.nanoTime() - previous) / blocksPerPoint.toFloat()
                    graphPoints.add(Entry(x, y))
                    previous = System.nanoTime()
                }
            }
            packetsSent = counter
        } else {
            var previous : Long = System.nanoTime()
            for (i in 0..limit) {
                BasicBlock(blockType, message, senderPublicKey, receiverPublicKey)
                if (i % blocksPerPoint == 0) {
                    val x = i.toFloat()
                    val y = (System.nanoTime() - previous) / blocksPerPoint.toFloat()
                    graphPoints.add(Entry(x, y))
                    previous = System.nanoTime()
                }
            }
        }
        val totalTime: Long = System.nanoTime() - startTime
        val totalTimeSeconds: Double = (totalTime / 1000000000).toDouble()
        val totalSize: Double = (message.size*packetsSent).toDouble()
        payloadBandwidth = totalSize / totalTimeSeconds
        return BenchmarkResult(graphPoints, totalTime, payloadBandwidth,0)
    }

    /**
     * Benchmarks unencrypted block creation with random content and random receiver addresses.
     * @param blocksPerPoint how many blocks will correspond to one point in the final graph
     * @param limit either the time limit (in milliseconds) or the total number of blocks for the
     * benchmark to run (based on [benchmarkByTime])
     * @param benchmarkByTime whether or not the benchmark will run for a specific time or for a
     * specific number of blocks (both specified by [limit])
     * @return a [BenchmarkResult] instance
     */
    fun unencryptedBasicRandom(blocksPerPoint: Int,
                               limit: Int,
                               benchmarkByTime: Boolean): BenchmarkResult {
        val type = "benchmarkRandom"
        val senderPublicKey : ByteArray = "fakeKey".toByteArray()

        val startTime = System.nanoTime()
        val graphPoints: ArrayList<Entry> = ArrayList()

        val random = Random()
        val randomMessage = ByteArray(DetoksConfig.RANDOM_MESSAGE_LENGTH)
        val randomReceiver = ByteArray(DetoksConfig.RANDOM_PUBLIC_KEY_LENGTH)

        var packetsSent: Int = limit
        val payloadBandwidth: Double

        if (benchmarkByTime) {
            var counter = 0
            var previous : Long = System.nanoTime()
            while (System.nanoTime() < (startTime) + limit.toLong() * 1000000) {

                // Generate random message and random receiver
                random.nextBytes(randomMessage)
                random.nextBytes(randomReceiver)

                BasicBlock(type, randomMessage, senderPublicKey, randomReceiver)
                counter++
                if (counter % blocksPerPoint == 0) {
                    val x = counter.toFloat()
                    val y = (System.nanoTime() - previous) / blocksPerPoint.toFloat()
                    graphPoints.add(Entry(x, y))
                    previous = System.nanoTime()
                }
            }
            packetsSent = counter
        } else {
            var previous : Long = System.nanoTime()
            for (i in 0..limit) {

                // Generate random message and random receiver
                random.nextBytes(randomMessage)
                random.nextBytes(randomReceiver)

                BasicBlock(type, randomMessage, senderPublicKey, randomReceiver)
                if (i % blocksPerPoint == 0) {
                    val x = i.toFloat()
                    val y = (System.nanoTime() - previous) / blocksPerPoint.toFloat()
                    graphPoints.add(Entry(x, y))
                    previous = System.nanoTime()
                }
            }
        }
        val totalTime: Long = System.nanoTime() - startTime
        val totalTimeSeconds: Double = (totalTime / 1000000000).toDouble()
        val totalSize: Double = (DetoksConfig.RANDOM_MESSAGE_LENGTH*packetsSent).toDouble()
        payloadBandwidth = totalSize / totalTimeSeconds
        return BenchmarkResult(graphPoints, totalTime, payloadBandwidth,0)
    }

    /**
     * Benchmarks unencrypted signed block creation with random content and random receiver
     * addresses.
     * @param senderPrivateKey the private key of the node that executes the benchmark
     * @param blocksPerPoint how many blocks will correspond to one point in the final graph
     * @param limit either the time limit (in milliseconds) or the total number of blocks for the
     * benchmark to run (based on [benchmarkByTime])
     * @param benchmarkByTime whether or not the benchmark will run for a specific time or for a
     * specific number of blocks (both specified by [limit])
     * @return a [BenchmarkResult] instance
     */
    fun unencryptedRandomSigned(senderPrivateKey: PrivateKey,
                                blocksPerPoint: Int,
                                limit: Int,
                                time: Boolean): BenchmarkResult {
        val type = "benchmarkRandomSigned"
        val senderPublicKey : ByteArray = senderPrivateKey.pub().keyToBin()

        val random = Random()
        val randomMessage = ByteArray(DetoksConfig.RANDOM_MESSAGE_LENGTH)
        val randomReceiver = ByteArray(DetoksConfig.RANDOM_PUBLIC_KEY_LENGTH)

        val startTime = System.nanoTime()

        val graphPoints: ArrayList<Entry> = ArrayList()
        var packetsSent: Int = limit
        val payloadBandwidth: Double

        if (time) {
            var counter = 0
            var previous: Long = System.nanoTime()
            while (System.nanoTime() < (startTime) + limit.toLong() * 1000000) {

                // Generate random message and random receiver
                random.nextBytes(randomMessage)
                random.nextBytes(randomReceiver)

                val block = BasicBlock(
                    type,
                    randomMessage,
                    senderPublicKey,
                    randomReceiver
                )
                block.sign(senderPrivateKey)
                counter++
                if (counter % blocksPerPoint == 0) {
                    val x = counter.toFloat()
                    val y = (System.nanoTime() - previous) / blocksPerPoint.toFloat()
                    graphPoints.add(Entry(x, y))
                    previous = System.nanoTime()
                }
            }
            packetsSent = counter
        } else {
            var previous : Long = System.nanoTime()
            for (i in 0..limit) {

                // Generate random message and random receiver
                random.nextBytes(randomMessage)
                random.nextBytes(randomReceiver)

                val block: BasicBlock = BasicBlock(
                    type,
                    randomMessage,
                    senderPublicKey,
                    randomReceiver
                )
                block.sign(senderPrivateKey)
                if (i % blocksPerPoint == 0) {
                    val x = i.toFloat()
                    val y = (System.nanoTime() - previous) / blocksPerPoint.toFloat()
                    graphPoints.add(Entry(x, y))
                    previous = System.nanoTime()
                }
            }
        }
        val totalTime: Long = System.nanoTime() - startTime
        val totalTimeSeconds: Double = (totalTime / 1000000000).toDouble()
        val totalSize: Double = (DetoksConfig.RANDOM_MESSAGE_LENGTH*packetsSent).toDouble()
        payloadBandwidth = totalSize / totalTimeSeconds
        return BenchmarkResult(graphPoints, totalTime, payloadBandwidth,0)
    }


    // this method can be used to benchmark the creation of signed blocks that are stored in memory. It creates TrustChain blocks as
    // opposed to the previously used BasicBlocks.
//     fun unencryptedRandomSignedTrustChain(receiverList: ArrayList<ByteArray>, messageList: ArrayList<ByteArray>, graphResolution: Int, numberOfBlocks: Int, time: Boolean) : BenchmarkResult {
//        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
//        Database.Schema.create(driver)
//        val store = TrustChainSQLiteStore(Database(driver))
//
//        val random = Random()
//        val startTime = System.nanoTime()
//
//        for (i in 0 .. 1000) {
//            val message = ByteArray(200)
//            random.nextBytes(message)
//
//            val receiverPublicKey = ByteArray(64)
//            random.nextBytes(receiverPublicKey)
//
//            val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned", messageList[i], receiverList[i])
//            blockBuilder.sign()
//
//        }
//    }

    /**
     * Benchmarks unencrypted signed block creation with random content and random receiver
     * addresses with permanent storage of blocks.
     * @param context the context that describes the state of the Android application. Will be used
     * to gain access to the Android SQLite permanent storage.
     * @param blocksPerPoint how many blocks will correspond to one point in the final graph
     * @param limit either the time limit (in milliseconds) or the total number of blocks for the
     * benchmark to run (based on [benchmarkByTime])
     * @param benchmarkByTime whether or not the benchmark will run for a specific time or for a
     * specific number of blocks (both specified by [limit])
     * @return a [BenchmarkResult] instance
     */
    fun unencryptedRandomSignedTrustchainPermanentStorage(
        context: Context,
        blocksPerPoint: Int,
        limit: Int,
        benchmarkByTime: Boolean
    ): BenchmarkResult {
        val driver = AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")
        val store = TrustChainSQLiteStore(Database(driver))

        val random = Random()
        val randomMessage = ByteArray(DetoksConfig.RANDOM_MESSAGE_LENGTH)
        val randomReceiver = ByteArray(DetoksConfig.RANDOM_PUBLIC_KEY_LENGTH)

        val startTime = System.nanoTime()

        val graphPoints : ArrayList<Entry> = ArrayList()
        var packetsSent = limit
        val payloadBandwidth: Double

        if (benchmarkByTime) {
            var counter = 0
            var previous: Long = System.nanoTime()
            while (System.nanoTime() < (startTime) + limit.toLong() * 1000000) {

                // Generate random message and random receiver
                random.nextBytes(randomMessage)
                random.nextBytes(randomReceiver)

                val blockBuilder = SimpleBlockBuilder(
                    myPeer,
                    store,
                    "benchmarkTrustchainSigned",
                    randomMessage,
                    randomReceiver
                )
                blockBuilder.sign()

                counter++
                if (counter % blocksPerPoint == 0) {
                    val x = counter.toFloat()
                    val y = (System.nanoTime() - previous) / blocksPerPoint.toFloat()
                    graphPoints.add(Entry(x, y))
                    previous = System.nanoTime()
                }
            }
            packetsSent = counter
        } else {
            var previous: Long = System.nanoTime()
            for (i in 0..limit) {

                // Generate random message and random receiver
                random.nextBytes(randomMessage)
                random.nextBytes(randomReceiver)

                val blockBuilder = SimpleBlockBuilder(
                    myPeer,
                    store,
                    "benchmarkTrustchainSigned",
                    randomMessage,
                    randomReceiver
                )
                blockBuilder.sign()

                if (i % blocksPerPoint == 0) {
                    val x = i.toFloat()
                    val y = (System.nanoTime() - previous) / blocksPerPoint.toFloat()
                    graphPoints.add(Entry(x, y))
                    previous = System.nanoTime()
                }
            }
        }

        val totalTime: Long = System.nanoTime() - startTime
        val totalTimeSeconds: Double = (totalTime / 1000000000).toDouble()
        val totalSize: Double = (DetoksConfig.RANDOM_MESSAGE_LENGTH*packetsSent).toDouble()
        payloadBandwidth = totalSize / totalTimeSeconds
        return BenchmarkResult(graphPoints, totalTime, payloadBandwidth,0)
    }

    // ============================== BLOCK SENDING METHODS ========================================

    // This method can be used to benchmark the sending of signed unencrypted blocks over ipv8
    fun unencryptedRandomContentSendIPv8(destinationPeer: Peer, context: Context?, graphResolution: Int, numberOfBlocks: Int, time: Boolean) : BenchmarkResult {
        val driver: SqlDriver = if(context!=null) {
            AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")
        } else {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        }

        val random = Random()
        var randomMessage = ByteArray(200)
        var randomReceiver = ByteArray(64)

        val store = TrustChainSQLiteStore(Database(driver))
        val startTime = System.nanoTime()
        val timePerBlock : ArrayList<Entry> = ArrayList()


        if (time) {
            var counter = 0
            var previous : Long = System.nanoTime()
            while (System.nanoTime() < (startTime) + numberOfBlocks.toLong() * 1000000) {

                // Generate random message and random receiver
                random.nextBytes(randomMessage)
                random.nextBytes(randomReceiver)

                val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned",
                    randomMessage, randomReceiver
                )
                blockBuilder.sign()
                txEngineUnderTest.sendTransaction(blockBuilder.sign(), destinationPeer, encrypt = false,MessageId.BLOCK_UNENCRYPTED,myPeer)
                counter++
                if (counter % graphResolution == 0) {
                    timePerBlock.add(Entry(counter.toFloat(), (System.nanoTime() - previous) / graphResolution.toFloat()))
                    previous = System.nanoTime()
                }
            }

            val totalTime : Long = System.nanoTime() - startTime
            val payloadBandwith : Double = (randomMessage.size * numberOfBlocks).toDouble() / (totalTime / 1000000000).toDouble()
            return BenchmarkResult(timePerBlock, totalTime, payloadBandwith,0)

        } else {
            var previous : Long = System.nanoTime()

            // Generate random message and random receiver
            random.nextBytes(randomMessage)
            random.nextBytes(randomReceiver)

            for (i in 0..numberOfBlocks) {
                val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned",
                    randomMessage, randomReceiver
                )
                blockBuilder.sign()
                txEngineUnderTest.sendTransaction(blockBuilder.sign(), destinationPeer, encrypt = false,MessageId.BLOCK_UNENCRYPTED,myPeer)
                if (i % graphResolution == 0) {
                    timePerBlock.add(Entry(i.toFloat(), (System.nanoTime() - previous) / graphResolution.toFloat()))
                    previous = System.nanoTime()

                }
            }
            val totalTime : Long = System.nanoTime() - startTime

            val payloadBandwith : Double = (randomMessage.size * numberOfBlocks).toDouble() / (totalTime / 1000000000).toDouble()
            return BenchmarkResult(timePerBlock, totalTime, payloadBandwith,0)
        }
    }

    // This method can be used to benchmark the sending of signed encrypted blocks over ipv8
    fun encryptedRandomContentSendIPv8(destinationPeer: Peer, context: Context?, graphResolution: Int, numberOfBlocks: Int, time: Boolean) : BenchmarkResult {
        val driver: SqlDriver = if(context!=null) {
            AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")
        } else {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        }

        val random = Random()
        var randomMessage = ByteArray(200)
        var randomReceiver = ByteArray(64)

        val store = TrustChainSQLiteStore(Database(driver))
        val startTime = System.nanoTime()
        val timePerBlock : ArrayList<Entry> = ArrayList()


        if (time) {
            var counter = 0
            var previous : Long = System.nanoTime()
            while (System.nanoTime() < (startTime) + numberOfBlocks.toLong() * 1000000) {

                // Generate random message and random receiver
                random.nextBytes(randomMessage)
                random.nextBytes(randomReceiver)

                val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned",
                    randomMessage, randomReceiver
                )
                blockBuilder.sign()
                txEngineUnderTest.sendTransaction(blockBuilder.sign(), destinationPeer, encrypt = true,MessageId.BLOCK_ENCRYPTED,myPeer)
                counter++
                if (counter % graphResolution == 0) {
                    timePerBlock.add(Entry(counter.toFloat(), (System.nanoTime() - previous) / graphResolution.toFloat()))
                    previous = System.nanoTime()
                }
            }

            val totalTime : Long = System.nanoTime() - startTime
            val payloadBandwith : Double = (randomMessage.size * numberOfBlocks).toDouble() / (totalTime / 1000000000).toDouble()
            return BenchmarkResult(timePerBlock, totalTime, payloadBandwith,0)

        } else {
            var previous : Long = System.nanoTime()
            for (i in 0..numberOfBlocks) {

                // Generate random message and random receiver
                random.nextBytes(randomMessage)
                random.nextBytes(randomReceiver)

                val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned",
                    randomMessage, randomReceiver
                )
                blockBuilder.sign()
                txEngineUnderTest.sendTransaction(blockBuilder.sign(), destinationPeer, encrypt = true,MessageId.BLOCK_ENCRYPTED,myPeer)
                if (i % graphResolution == 0) {
                    timePerBlock.add(Entry(i.toFloat(), (System.nanoTime() - previous) / graphResolution.toFloat()))
                    previous = System.nanoTime()

                }
            }
            val totalTime : Long = System.nanoTime() - startTime

            val payloadBandwith : Double = (randomMessage.size * numberOfBlocks).toDouble() / (totalTime / 1000000000).toDouble()
            return BenchmarkResult(timePerBlock, totalTime, payloadBandwith,0)
        }
    }

    // This method can be used to benchmark the receiving of signed encrypted blocks over ipv8
    fun encryptedRandomContentReceiveIPv8(destinationPeer: Peer, context: Context?, graphResolution: Int, numberOfBlocks: Int, time: Boolean) : BenchmarkResult {
        incomingBlockEchos.clear()
        val startTime = System.nanoTime()
        val timePerBlock : ArrayList<Entry> = ArrayList()
        val res = encryptedRandomContentSendIPv8(destinationPeer, context, graphResolution, numberOfBlocks, time)

        println("Waiting for 10 seconds to receive incoming blocks")
        Thread.sleep(10000) // wait for 10 seconds
        println("Done waiting.")

        val numReceived = incomingBlockEchos.size

        for (i in 0..numReceived) {
            if (i % graphResolution == 0) {
                timePerBlock.add(Entry(i.toFloat(), (System.nanoTime() - incomingBlockEchos[i]) / graphResolution.toFloat()))
            }
        }
        val totalTime : Long = startTime - incomingBlockEchos.max()
        val lostPackets = res.timePerBlock.size - incomingBlockEchos.size

        val payloadBandwith : Double = (200 * numReceived).toDouble() / (totalTime / 1000000000).toDouble()
        return BenchmarkResult(timePerBlock, totalTime, payloadBandwith,lostPackets.toLong())
    }

    fun registerTrustChainBenchmarkListenerSigner(trustchainInstance: TrustChainCommunity, privateKey: PrivateKey) {
        trustchainInstance.addListener("benchmark_block", object : BlockListener {
            override fun onBlockReceived(block: TrustChainBlock) {
                if (block.isAgreement && block.publicKey.toHex() !=  AndroidCryptoProvider.keyFromPrivateBin(privateKey.keyToBin()).pub().keyToBin().toHex()) {
                    val benchmarkIndex : Int = Integer.parseInt(block.transaction["message"].toString().drop(9).take(3))
                    println("benchmark: received $benchmarkIndex and index $benchmarkCounter")
                    benchmarkCounter++
                    trustchainSendTimeHashmap[benchmarkIndex]?.let { Entry(benchmarkIndex.toFloat(), it.toFloat()) }
                        ?.let { trustchainIPv8graphData.add(it) }
                    if (benchmarkIndex > highestReceivedBenchmarkCounter) {
                        highestReceivedBenchmarkCounter = benchmarkIndex
                    }
                    latestReceivedAgreement = System.nanoTime()
                    println("received benchmark $benchmarkIndex")
                }
            }
        })
    }

    fun trustchainIpv8Benchmark(trustchainInstance: TrustChainCommunity, peer: Peer, privateKey: PrivateKey, timeout: Long) : BenchmarkResult {

        val benchmarkIdentifier: String = UUID.randomUUID().toString()
        currentBenchmarkIdentifier = benchmarkIdentifier
        benchmarkStartTime = System.nanoTime()
        benchmarkCounter = 0

        registerTrustChainBenchmarkListenerSigner(trustchainInstance, privateKey)

        Thread(Runnable {
            for (i in 0..999) {
                val index : String = i.toString().padStart(3, '0')
                val transaction = mapOf("message" to "benchmark$index-$benchmarkIdentifier")
                trustchainInstance.createProposalBlock("benchmark_block", transaction, peer.publicKey.keyToBin())
                trustchainSendTimeHashmap[i] = System.nanoTime()
            }
        }).start()

        Thread.sleep(timeout)

        return BenchmarkResult(trustchainIPv8graphData,  latestReceivedAgreement - benchmarkStartTime, 0.0, 0)

    }

    //========Message Handlers==========
    // This method is used to track incoming encrypted blocks and echo then with a new timestamp
    private fun onEncryptedRandomIPv8Packet(packet: Packet) {
        val incomingTime = System.nanoTime()
        val (peer, payload) = packet.getAuthPayload(HalfBlockPayload.Deserializer)

        val echoPayload = HalfBlockPayload(payload.publicKey,payload.sequenceNumber,payload.linkPublicKey,payload.linkSequenceNumber,payload.previousHash,payload.signature,payload.blockType,payload.transaction,
            incomingTime.toULong()
        )
        println("received")
        txEngineUnderTest.sendTransaction(echoPayload.toBlock(), peer, encrypt = true,MessageId.BLOCK_ENCRYPTED_ECHO,myPeer)
    }
    // This method is used to track incoming encrypted block echos and store them for benchmarking
    private fun onEncryptedEchoPacket(packet: Packet) {
        val payload = packet.getPayload(HalfBlockPayload.Deserializer)
        incomingBlockEchos.add(payload.timestamp.toLong())
    }
}
