package nl.tudelft.trustchain.detoks.benchmark

import android.content.Context
import com.squareup.sqldelight.android.AndroidSqliteDriver
import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockPayload
import nl.tudelft.ipv8.attestation.trustchain.store.TrustChainSQLiteStore
import nl.tudelft.ipv8.keyvault.PrivateKey
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.sqldelight.Database
import kotlin.collections.ArrayList
import com.github.mikephil.charting.data.Entry
import nl.tudelft.trustchain.detoks.BasicBlock
import nl.tudelft.trustchain.detoks.TransactionEngine
import mu.KotlinLogging
import nl.tudelft.trustchain.detoks.DetoksConfig
import java.util.*


/**
 * This is a class for creating benchmarks for a given TransactionEngine instance.
 */
open class TransactionEngineBenchmark(
    private val txEngineUnderTest: TransactionEngine,
    private val myPeer: Peer
) {
    object MessageId {
        const val BLOCK_UNENCRYPTED = 101
        const val BLOCK_ENCRYPTED = 102
        const val BLOCK_ENCRYPTED_ECHO = 103
    }

    private val logger = KotlinLogging.logger {}
    private val incomingBlockEchos = mutableListOf<Long>()

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

    /**
     * Benchmarks unencrypted signed block creation with random content and random receiver
     * addresses with permanent storage of blocks.
     * @param receiverPeer the peer towards which the benchmark will send all the blocks
     * @param context the context that describes the state of the Android application. Will be used
     * to gain access to the Android SQLite permanent storage.
     * @param blocksPerPoint how many blocks will correspond to one point in the final graph
     * @param limit either the time limit (in milliseconds) or the total number of blocks for the
     * benchmark to run (based on [benchmarkByTime])
     * @param benchmarkByTime whether or not the benchmark will run for a specific time or for a
     * specific number of blocks (both specified by [limit])
     * @return a [BenchmarkResult] instance
     */
    fun unencryptedRandomContentSendIPv8(receiverPeer: Peer,
                                         context: Context,
                                         graphResolution: Int,
                                         numberOfBlocks: Int,
                                         time: Boolean): BenchmarkResult {
        val driver = AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")

        val random = Random()
        val randomMessage = ByteArray(DetoksConfig.RANDOM_MESSAGE_LENGTH)
        val randomLink = ByteArray(DetoksConfig.RANDOM_PUBLIC_KEY_LENGTH)

        val store = TrustChainSQLiteStore(Database(driver))
        val startTime = System.nanoTime()
        val graphPoints: ArrayList<Entry> = ArrayList()

        if (time) {
            var counter = 0
            var previous : Long = System.nanoTime()
            while (System.nanoTime() < (startTime) + numberOfBlocks.toLong() * 1000000) {

                // Generate random message and random receiver
                random.nextBytes(randomMessage)
                random.nextBytes(randomLink)

                val blockBuilder = SimpleBlockBuilder(
                    myPeer,
                    store,
                    "benchmarkTrustchainSigned",
                    randomMessage,
                    randomLink
                )
                blockBuilder.sign()
                txEngineUnderTest.sendTransaction(blockBuilder.sign(), receiverPeer, encrypt = false,MessageId.BLOCK_UNENCRYPTED,myPeer)
                counter++
                if (counter % graphResolution == 0) {
                    graphPoints.add(Entry(counter.toFloat(), (System.nanoTime() - previous) / graphResolution.toFloat()))
                    previous = System.nanoTime()
                }
            }

            val totalTime : Long = System.nanoTime() - startTime
            val payloadBandwith : Double = (randomMessage.size * numberOfBlocks).toDouble() / (totalTime / 1000000000).toDouble()
            return BenchmarkResult(graphPoints, totalTime, payloadBandwith,0)

        } else {
            var previous : Long = System.nanoTime()

            // Generate random message and random receiver
            random.nextBytes(randomMessage)
            random.nextBytes(randomLink)

            for (i in 0..numberOfBlocks) {
                val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned",
                    randomMessage, randomLink
                )
                blockBuilder.sign()
                txEngineUnderTest.sendTransaction(blockBuilder.sign(), receiverPeer, encrypt = false,MessageId.BLOCK_UNENCRYPTED,myPeer)
                if (i % graphResolution == 0) {
                    graphPoints.add(Entry(i.toFloat(), (System.nanoTime() - previous) / graphResolution.toFloat()))
                    previous = System.nanoTime()

                }
            }
            val totalTime : Long = System.nanoTime() - startTime

            val payloadBandwith : Double = (randomMessage.size * numberOfBlocks).toDouble() / (totalTime / 1000000000).toDouble()
            return BenchmarkResult(graphPoints, totalTime, payloadBandwith,0)
        }
    }

    // This method can be used to benchmark the sending of signed encrypted blocks over ipv8
    fun encryptedRandomContentSendIPv8(key: PrivateKey, destinationPeer: Peer, context: Context?, receiverList: ArrayList<ByteArray>, messageList: ArrayList<ByteArray>, graphResolution: Int, numberOfBlocks: Int, time: Boolean) : BenchmarkResult {
        val driver: SqlDriver = if(context!=null) {
            AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")
        } else {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        }

        val store = TrustChainSQLiteStore(Database(driver))
        val startTime = System.nanoTime()
        val timePerBlock : ArrayList<Entry> = ArrayList()


        if (time) {
            var counter = 0
            var previous : Long = System.nanoTime()
            while (System.nanoTime() < (startTime) + numberOfBlocks.toLong() * 1000000) {
                val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned",
                    messageList[counter%messageList.size], receiverList[counter%receiverList.size]
                )
                blockBuilder.sign()
                txEngineUnderTest.sendTransaction(blockBuilder.sign(), destinationPeer, encrypt = true,
                    MessageId.BLOCK_ENCRYPTED
                )
                counter++
                if (counter % graphResolution == 0) {
                    timePerBlock.add(Entry(counter.toFloat(), (System.nanoTime() - previous) / graphResolution.toFloat()))
                    previous = System.nanoTime()
                }
            }

            val totalTime : Long = System.nanoTime() - startTime
            val payloadBandwith : Double = (messageList[0].size * numberOfBlocks).toDouble() / (totalTime / 1000000000).toDouble()
            return BenchmarkResult(timePerBlock, totalTime, payloadBandwith,0)

        } else {
            var previous : Long = System.nanoTime()
            for (i in 0..numberOfBlocks) {
                val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned",
                    messageList[i], receiverList[i]
                )
                blockBuilder.sign()
                txEngineUnderTest.sendTransaction(blockBuilder.sign(), destinationPeer, encrypt = true,
                    MessageId.BLOCK_ENCRYPTED
                )
                if (i % graphResolution == 0) {
                    timePerBlock.add(Entry(i.toFloat(), (System.nanoTime() - previous) / graphResolution.toFloat()))
                    previous = System.nanoTime()

                }
            }
            val totalTime : Long = System.nanoTime() - startTime

            val payloadBandwith : Double = (messageList[0].size * numberOfBlocks).toDouble() / (totalTime / 1000000000).toDouble()
            return BenchmarkResult(timePerBlock, totalTime, payloadBandwith,0)
        }
    }

    // This method can be used to benchmark the receiving of signed encrypted blocks over ipv8
    fun encryptedRandomContentReceiveIPv8(key: PrivateKey, destinationPeer: Peer, context: Context?, receiverList: ArrayList<ByteArray>, messageList: ArrayList<ByteArray>, graphResolution: Int, numberOfBlocks: Int, time: Boolean) : BenchmarkResult {
        incomingBlockEchos.clear()
        val startTime = System.nanoTime()
        val timePerBlock : ArrayList<Entry> = ArrayList()
        val res = encryptedRandomContentSendIPv8(key,destinationPeer,context,receiverList,messageList,graphResolution,numberOfBlocks,time)

        println("Waiting for 10 seconds to receive incoming blocks")
        Thread.sleep(10000) // wait for 10 seconds
        println("Done waiting.")

        val numReceived = incomingBlockEchos.size

        for (i in 0..numReceived) {
            if (i % graphResolution == 0) {
                timePerBlock.add(Entry(i.toFloat(), (System.nanoTime() - incomingBlockEchos[i]) / graphResolution.toFloat()))
            }
        }
        val totalTime : Long = System.nanoTime() - incomingBlockEchos.max()
        val lostPackets = res.timePerBlock.size - incomingBlockEchos.size

        val payloadBandwith : Double = (messageList[0].size * numReceived).toDouble() / (totalTime / 1000000000).toDouble()
        return BenchmarkResult(timePerBlock, totalTime, payloadBandwith,lostPackets.toLong())
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
        txEngineUnderTest.sendTransaction(echoPayload.toBlock(), peer, encrypt = true,
            MessageId.BLOCK_ENCRYPTED_ECHO
        )
    }
    // This method is used to track incoming encrypted block echos and store them for benchmarking
    private fun onEncryptedEchoPacket(packet: Packet) {
        val payload = packet.getPayload(HalfBlockPayload.Deserializer)
        incomingBlockEchos.add(payload.timestamp.toLong())
    }
}
