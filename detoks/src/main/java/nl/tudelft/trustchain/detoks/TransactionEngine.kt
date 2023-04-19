package nl.tudelft.trustchain.detoks

import android.content.Context
import android.util.Log
import com.squareup.sqldelight.android.AndroidSqliteDriver
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.attestation.trustchain.BlockBuilder
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.attestation.trustchain.UNKNOWN_SEQ
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockPayload
import nl.tudelft.ipv8.attestation.trustchain.store.TrustChainSQLiteStore
import nl.tudelft.ipv8.keyvault.PrivateKey
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.sqldelight.Database
import java.util.*
import kotlin.collections.ArrayList
import com.github.mikephil.charting.data.Entry
import kotlinx.android.synthetic.main.transactions_fragment_layout.*
import nl.tudelft.ipv8.attestation.trustchain.*
import nl.tudelft.ipv8.messaging.Address
import nl.tudelft.ipv8.util.toHex
import kotlin.collections.HashMap

open class TransactionEngine (override val serviceId: String): Community() {

    lateinit var trustchainInstance: TrustChainCommunity

    companion object {
        const val MESSAGE_TRANSACTION_ID = 2
    }
    /**
     * Broadcasts a token transaction to all known peers.
     * @param amount the amount of tokens to send
     * @param senderMid the member ID of the peer that sends the amount of tokens
     * @param recipientMid the member ID of the peer that will receive the amount of tokens
     */
    fun broadcastTokenTransaction(amount: Int,
                                  senderMid: String,
                                  recipientMid: String) {
        for (peer in getPeers()) {
            sendTokenTransaction(amount, senderMid, recipientMid, peer.address)
        }
    }

    /**
     * Sends a token transaction to a peer.
     * @param amount the amount of tokens to be sent
     * @param senderMid the member ID of the peer that sends the amount of tokens
     * @param recipientMid the member ID of the peer that will receive the amount of tokens
     * @param receiverAddress the address of the peer that receives the transaction. That
     * peer may be different than the recipient of the amount of tokens in the transaction
     */
    fun sendTokenTransaction(amount: Int,
                             senderMid: String,
                             recipientMid: String,
                             receiverAddress: Address) {
        val packet = serializePacket(
            MESSAGE_TRANSACTION_ID,
            TransactionMessage(amount, senderMid, recipientMid)
        )
        send(receiverAddress, packet)
    }

    fun sendTransaction(block: TrustChainBlock,
                        peer: Peer,
                        encrypt: Boolean = false,
                        msgID: Int) {
        Log.d("TransactionEngine",
              "Creating transaction to peer with public key: " + peer.key.pub().toString())
        sendBlockToRecipient(peer, block, encrypt, msgID)
    }

    fun addReceiver(onMessageId: Int, receiver: (Packet) -> Unit) {
        messageHandlers[msgIdFixer(onMessageId)] = receiver
    }

    private fun sendBlockToRecipient(peer: Peer,
                                     block: TrustChainBlock,
                                     encrypt: Boolean,
                                     msgID: Int) {
        val payload = HalfBlockPayload.fromHalfBlock(block)

        val data = if (encrypt) {
            serializePacket(
                msgID, payload, false, encrypt = true, recipient = peer, peer = myPeer
            )
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
            Log.d("TransactionEngine",
                "Received unknown message $msgId from $sourceAddress")
        }
    }

    private fun msgIdFixer(msgid: Int): Int{
        @Suppress("DEPRECATION")
        return msgid.toChar().toByte().toInt()
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

open class TransactionEngineBenchmark(val txEngineUnderTest: TransactionEngine) {
    object MessageId {
        const val BLOCK_UNENCRYPTED = 35007
        const val BLOCK_ENCRYPTED = 350072
        const val BLOCK_ENCRYPTED_ECHO = 3500721
    }

    private val incomingBlockEchos = mutableListOf<Long>()

    var receivedAgreementCounter: Int = 0
    val trustchainIPv8GraphPoints: ArrayList<Entry> = ArrayList()
    val trustchainSendTimeHashmap : HashMap<Int, Long> = HashMap()
    var latestReceivedAgreement : Long = 0

    init{
        txEngineUnderTest.addReceiver(MessageId.BLOCK_ENCRYPTED, ::onEncryptedRandomIPv8Packet)
        txEngineUnderTest.addReceiver(MessageId.BLOCK_ENCRYPTED_ECHO, ::onEncryptedEchoPacket)
        registerTrustChainBenchmarkProposalListener()
        registerTrustChainBenchmarkAgreementListener()
    }

    /**
     * Benchmarks parameterized block creation and sending.
     * @param signed whether to sign the blocks or not
     * @param randomContent whether the blocks contain random message and addresses or not
     * @param storage the type of storage to use in order to store the blocks. Can be "permanent",
     * "in-memory" or "no-storage"
     * @param context the Context of the application
     * @param destinationPeer the peer of the "DeToksCommunity" towards which to send the blocks. If
     * null, then we have no IPv8 involvement (only creation of blocks)
     * @param encrypted whether to encrypt the blocks or not (for IPv8 involvement only)
     * @param blocksPerPoint how many blocks will correspond to one point in the final graph
     * @param limit either the time limit (in milliseconds) or the total number of blocks for the
     * benchmark to run (based on [benchmarkByTime])
     * @param benchmarkByTime whether or not the benchmark will run for a specific time or for a
     * specific number of blocks (both specified by [limit])
     * @return a [BenchmarkResult] instance
     */
    fun runBenchmark(
        destinationPeer: Peer?,
        randomContent: Boolean,
        storage: String,
        encrypted: Boolean = false,
        context: Context,
        blocksPerPoint: Int,
        limit: Int,
        benchmarkByTime: Boolean,
        signed: Boolean
    ): BenchmarkResult {
        val driver = AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")
        val store = TrustChainSQLiteStore(Database(driver))
        val graphPoints: ArrayList<Entry> = ArrayList()
        val blockType = "benchmark"
        val senderPrivateKey: PrivateKey = txEngineUnderTest.myPeer.key as PrivateKey
        val senderPublicKey: ByteArray = senderPrivateKey.pub().keyToBin()
        val message = ByteArray(DetoksConfig.DEFAULT_MESSAGE_LENGTH)
        val receiverPublicKey = ByteArray(DetoksConfig.DEFAULT_PUBLIC_KEY_LENGTH)
        val random = Random()
        val blockList: ArrayList<BasicBlock> = ArrayList()
        val startTime = System.nanoTime()
        var packetsSent: Int = limit
        val payloadBandwidth: Double
        var previous: Long = System.nanoTime()

        if (benchmarkByTime) {
            var counter = 0
            while (System.nanoTime() < (startTime) + limit.toLong() * 1000000) {
                if (randomContent) {
                    // Generate random message and random receiver
                    random.nextBytes(message)
                    random.nextBytes(receiverPublicKey)
                }
                if (storage == "permanent") {
                    val blockBuilder = SimpleBlockBuilder(
                        txEngineUnderTest.myPeer,
                        store,
                        blockType,
                        message,
                        receiverPublicKey
                    )
                    val block = blockBuilder.sign()
                    if (destinationPeer != null) {
                        txEngineUnderTest.sendTransaction(
                            block,
                            destinationPeer,
                            encrypt = encrypted,
                            MessageId.BLOCK_ENCRYPTED
                        )
                    }
                } else {
                    val block = BasicBlock(blockType, message, senderPublicKey, receiverPublicKey)
                    if (signed) {
                        block.sign(senderPrivateKey)
                    }
                    if (storage == "in-memory") {
                        blockList.add(block)
                    }
                }
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
            for (i in 0..limit) {
                if (randomContent) {
                    // Generate random message and random receiver
                    random.nextBytes(message)
                    random.nextBytes(receiverPublicKey)
                }
                if (storage == "permanent") {
                    val blockBuilder = SimpleBlockBuilder(
                        txEngineUnderTest.myPeer,
                        store,
                        blockType,
                        message,
                        receiverPublicKey
                    )
                    val block = blockBuilder.sign()
                    if (destinationPeer != null) {
                        txEngineUnderTest.sendTransaction(
                            block,
                            destinationPeer,
                            encrypt = encrypted,
                            MessageId.BLOCK_ENCRYPTED
                        )
                    }
                } else {
                    val block = BasicBlock(blockType, message, senderPublicKey, receiverPublicKey)
                    if (signed) {
                        block.sign(senderPrivateKey)
                    }
                    if (storage == "in-memory") {
                        blockList.add(block)
                    }
                }
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

    private fun registerTrustChainBenchmarkProposalListener() {
        txEngineUnderTest.trustchainInstance.registerBlockSigner(
            "benchmark_block",
            object: BlockSigner {
                override fun onSignatureRequest(block: TrustChainBlock) {
                    txEngineUnderTest.trustchainInstance.createAgreementBlock(
                        block,
                        mapOf("message" to block.transaction["message"])
                    )
            }
        })
    }

    private fun registerTrustChainBenchmarkAgreementListener() {
        txEngineUnderTest.trustchainInstance.addListener(
            "benchmark_block",
            object : BlockListener {
                override fun onBlockReceived(block: TrustChainBlock) {
                    val myPublicKey = txEngineUnderTest.myPeer.key.pub().keyToBin().toHex()
                    if (block.isAgreement && block.publicKey.toHex() != myPublicKey) {
                        val blockIndex: Int = Integer.parseInt(
                            block.transaction["message"].toString())
                        receivedAgreementCounter++
                        Log.d("TxEngineBenchmark",
                            "Received benchmark agreement block: $blockIndex" +
                                 " (total agreements received: $receivedAgreementCounter)")
                        if (trustchainSendTimeHashmap.containsKey(blockIndex)) {
                            trustchainIPv8GraphPoints.add(Entry(
                                    blockIndex.toFloat(),
                                    trustchainSendTimeHashmap[blockIndex]!!.toFloat()
                                )
                            )
                        }
                        latestReceivedAgreement = System.nanoTime()
                    }
                }
            }
        )
    }

    fun trustchainIpv8Benchmark(destinationPeer: Peer,
                                limit: Int,
                                benchmarkByTime: Boolean,): BenchmarkResult {
        val startTime = System.nanoTime()
        receivedAgreementCounter = 0

        Thread(Runnable {

            if (benchmarkByTime) {
                var counter = 0
                while (System.nanoTime() < (startTime) + limit.toLong() * 1000000) {
                    val index: String = counter.toString().padStart(3, '0')
                    val transaction = mapOf("message" to "$index")
                    txEngineUnderTest.trustchainInstance.createProposalBlock(
                        "benchmark_block",
                        transaction,
                        destinationPeer.publicKey.keyToBin()
                    )
                    trustchainSendTimeHashmap[counter] = System.nanoTime()
                    counter++
                }
            } else {
                for (i in 0 until limit) {
                    val index: String = i.toString().padStart(3, '0')
                    val transaction = mapOf("message" to "$index")
                    txEngineUnderTest.trustchainInstance.createProposalBlock(
                        "benchmark_block",
                        transaction,
                        destinationPeer.publicKey.keyToBin()
                    )
                    trustchainSendTimeHashmap[i] = System.nanoTime()
                }
            }
        }).start()

        Thread.sleep(10000)

        return BenchmarkResult(
            trustchainIPv8GraphPoints,
            latestReceivedAgreement - startTime,
            0.0,
            1000 - receivedAgreementCounter
        )
    }

    //========Message Handlers==========
    // This method is used to track incoming encrypted blocks and echo then with a new timestamp
    private fun onEncryptedRandomIPv8Packet(packet: Packet) {
        val incomingTime = System.nanoTime()
        val (peer, payload) = packet.getAuthPayload(HalfBlockPayload.Deserializer)

        val echoPayload = HalfBlockPayload(
            payload.publicKey,
            payload.sequenceNumber,
            payload.linkPublicKey,
            payload.linkSequenceNumber,
            payload.previousHash,
            payload.signature,
            payload.blockType,
            payload.transaction,
            incomingTime.toULong()
        )
        Log.d("TxEngineBenchmark",
            "Received packet from peer:" + peer.key.pub().toString())
        txEngineUnderTest.sendTransaction(
            echoPayload.toBlock(),
            peer,
            encrypt = true,
            MessageId.BLOCK_ENCRYPTED_ECHO
        )
    }
    // This method is used to track incoming encrypted block echos and store them for benchmarking
    private fun onEncryptedEchoPacket(packet: Packet) {
        val payload = packet.getPayload(HalfBlockPayload.Deserializer)
        incomingBlockEchos.add(payload.timestamp.toLong())
    }
}
