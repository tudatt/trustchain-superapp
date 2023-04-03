package nl.tudelft.trustchain.detoks

import android.content.Context
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
import nl.tudelft.ipv8.messaging.payload.IntroductionRequestPayload
import nl.tudelft.ipv8.sqldelight.Database
import nl.tudelft.ipv8.util.random
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.min
import com.github.mikephil.charting.data.Entry

open class TransactionEngine (override val serviceId: String): Community() {

    fun sendTransaction(block: TrustChainBlock, peer: Peer, encrypt: Boolean = false, msgID: Int) {
        println("sending block...")
        sendBlockToRecipient(peer, block, encrypt,msgID)
    }

    fun addReceiver(onMessageId: Int, receiver: (Packet) -> Unit) {
        messageHandlers[msgIdFixer(onMessageId)] = receiver
    }

    private fun sendBlockToRecipient(peer: Peer, block: TrustChainBlock, encrypt: Boolean, msgID: Int) {
        val payload = HalfBlockPayload.fromHalfBlock(block)

        val data = if (encrypt) {
            serializePacket(msgID, payload, false, encrypt = true, recipient = peer)
        } else {
            serializePacket(msgID, payload, false)
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

    init{
        txEngineUnderTest.addReceiver(MessageId.BLOCK_ENCRYPTED, ::onEncryptedRandomIPv8Packet)
        txEngineUnderTest.addReceiver(MessageId.BLOCK_ENCRYPTED_ECHO, ::onEncryptedEchoPacket)
    }
    // ======================== BLOCK CREATION METHODS =========================

    // this method can be used to benchmark unencrypted Basic block creation with the same content and the same addresses
     fun unencryptedBasicSameContent(graphResolution : Int, numberOfBlocks : Int, time : Boolean) : BenchmarkResult{
        val timePerBlock : ArrayList<Entry> = ArrayList()
        val type : String = "benchmark"
        val message : ByteArray = "benchmarkMessage".toByteArray()
        val senderPublicKey : ByteArray = "fakeKey".toByteArray()
        val receiverPublicKey : ByteArray = "fakeReceiverKey".toByteArray()
        var startTime = System.nanoTime()

        var blockList : ArrayList<BasicBlock> = ArrayList()

        if (time) {
            var counter = 0
            var previous : Long = System.nanoTime()
            while (System.nanoTime() < (startTime) + numberOfBlocks.toLong() * 1000000) {
                blockList.add(BasicBlock(type, message, senderPublicKey, receiverPublicKey, "".toByteArray()))
                counter++
                if (counter % graphResolution == 0) {
                    timePerBlock.add(Entry(counter.toFloat(), (System.nanoTime() - previous) / graphResolution.toFloat()))
                    previous = System.nanoTime()
                }
            }

            val totalTime : Long = System.nanoTime() - startTime
            val payloadBandwith : Double = (message.size * numberOfBlocks).toDouble() / (totalTime / 1000000000).toDouble()
            return BenchmarkResult(timePerBlock, totalTime, payloadBandwith)

        } else {
            var previous : Long = System.nanoTime()
            for (i in 0..numberOfBlocks) {
                blockList.add(BasicBlock(type, message, senderPublicKey, receiverPublicKey, "".toByteArray()))
                println("index: " + i.toString() + " resolution " + graphResolution.toString() + " modulo " + (i % graphResolution))
                if (i % graphResolution == 0) {
                    timePerBlock.add(Entry(i.toFloat(), (System.nanoTime() - previous) / graphResolution.toFloat()))
                    previous = System.nanoTime()

                }
            }
            val totalTime : Long = System.nanoTime() - startTime

            val payloadBandwith : Double = (message.size * numberOfBlocks).toDouble() / (totalTime / 1000000000).toDouble()
            return BenchmarkResult(timePerBlock, totalTime, payloadBandwith)
        }
    }

    // this method can be use to benchmark unencrypted basic block creation with random content and random addresses.
    // We assume 64 byte public key.
    fun unencryptedBasicRandom(receiverList: ArrayList<ByteArray>, messageList: ArrayList<ByteArray>) : Long {
        val type : String = "benchmarkRandom"
        val senderPublicKey : ByteArray = "fakeKey".toByteArray()

        // In order to avoid compiler optimisations, we store the blocks in a list and print the size of that list.
        var blockList : ArrayList<BasicBlock> = ArrayList()

        val startTime = System.nanoTime()

        for (i in 0 .. 1000) {

            blockList.add(BasicBlock(type, messageList[i], senderPublicKey, receiverList[i], "".toByteArray()))
        }

        println(blockList.size)

        return System.nanoTime() - startTime
    }

    // This method will be used to benchmark the creation of random blocks with a signature.

     fun unencryptedRandomSigned(key : PrivateKey, receiverList: ArrayList<ByteArray>, messageList: ArrayList<ByteArray>) : Long {
        val type : String = "benchmarkRandomSigned"
        val senderPublicKey : ByteArray = key.pub().keyToBin()

        val blockList : ArrayList<BasicBlock> = ArrayList()


         val startTime = System.nanoTime()

        for (i in 0 .. 1000) {


            val block : BasicBlock = (BasicBlock(type, messageList[i], senderPublicKey, receiverList[i], ByteArray(0)))
            block.sign(key)
            blockList.add(block)
        }

        println(blockList.size)

        return System.nanoTime() - startTime
    }

    // this method can be used to benchmark the creation of signed blocks that are stored in memory. It creates TrustChain blocks as
    // opposed to the previously used BasicBlocks.
     fun unencryptedRandomSignedTrustChain(receiverList: ArrayList<ByteArray>, messageList: ArrayList<ByteArray>) : Long {
        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val store = TrustChainSQLiteStore(Database(driver))

        val random = Random()
        val startTime = System.nanoTime()

        for (i in 0 .. 1000) {
            val message = ByteArray(200)
            random.nextBytes(message)

            val receiverPublicKey = ByteArray(64)
            random.nextBytes(receiverPublicKey)

            val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned", messageList[i], receiverList[i])
            blockBuilder.sign()

        }
        return System.nanoTime() - startTime
    }

    // This method can be used to benchmark the creation of signed blocks that are stored in a proper database.
    fun unencryptedRandomSignedTrustchainPermanentStorage(context: Context, receiverList: ArrayList<ByteArray>, messageList: ArrayList<ByteArray>) : Long {
        val driver = AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")
        val store = TrustChainSQLiteStore(Database(driver))


        val startTime = System.nanoTime()

        for (i in 0 .. 1000) {


            val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned",
                messageList[i], receiverList[i]
            )
            blockBuilder.sign()
        }
        return System.nanoTime() - startTime
    }

    // ============================== BLOCK SENDING METHODS ========================================

    // This method can be used to benchmark the sending of signed unencrypted blocks over ipv8
    fun unencryptedRandomContentSendIPv8(key: PrivateKey, context: Context?, destinationPeer: Peer, messageList: ArrayList<ByteArray>) : Long {
        incomingBlockEchos.clear()
        val driver: SqlDriver = if(context!=null) {
            AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")
        } else {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        }

        val store = TrustChainSQLiteStore(Database(driver))
        val random = Random()
        val startTime = System.nanoTime()


        for (i in 0 .. 1000) {
            val message = ByteArray(200)
            random.nextBytes(message)
            val receiverPublicKey = ByteArray(64)
            random.nextBytes(receiverPublicKey)

            val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned", messageList.get(i), key.pub().keyToBin())

            txEngineUnderTest.sendTransaction(blockBuilder.sign(), destinationPeer, encrypt = false,MessageId.BLOCK_UNENCRYPTED)
        }
        return System.nanoTime() - startTime
    }

    // This method can be used to benchmark the sending of signed encrypted blocks over ipv8
    fun encryptedRandomContentSendIPv8(key: PrivateKey, context: Context?, destinationPeer: Peer) : Long {
        incomingBlockEchos.clear()
        val driver: SqlDriver = if(context!=null) {
            AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")
        } else {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        }
        val store = TrustChainSQLiteStore(Database(driver))
        val random = Random()
        val startTime = System.nanoTime()

        for (i in 0 .. 1000) {
            val message = ByteArray(200)
            random.nextBytes(message)

            val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned", message, key.pub().keyToBin())

            txEngineUnderTest.sendTransaction(blockBuilder.sign(), destinationPeer, encrypt = true,MessageId.BLOCK_ENCRYPTED)
        }
        return System.nanoTime() - startTime
    }

    // This method can be used to benchmark the receiving of signed encrypted blocks over ipv8
    fun encryptedRandomReceiveIPv8(key: PrivateKey, context: Context?, peer: Peer, timeout: Long) : Pair<Long, Int> {
        val startTime = System.nanoTime()
        encryptedRandomContentSendIPv8(key,context,peer)
        println("Waiting for $timeout seconds to receive incoming blocks")
        Thread.sleep(timeout*1000) // wait for 10 seconds
        println("Done waiting.")
        val loss = 1000-incomingBlockEchos.size
        return Pair(incomingBlockEchos.max()-startTime,loss)
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
        txEngineUnderTest.sendTransaction(echoPayload.toBlock(), peer, encrypt = true,MessageId.BLOCK_ENCRYPTED_ECHO)
    }
    // This method is used to track incoming encrypted block echos and store them for benchmarking
    private fun onEncryptedEchoPacket(packet: Packet) {
        val payload = packet.getPayload(HalfBlockPayload.Deserializer)
        incomingBlockEchos.add(payload.timestamp.toLong())
    }
}
