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
import nl.tudelft.ipv8.sqldelight.Database
import nl.tudelft.ipv8.util.random
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.min

open class TransactionEngine (override val serviceId: String): Community() {
    private val broadcastFanOut = 25
    private val ttl = 100

    object MessageId {
        const val HALF_BLOCK = 4242
        const val HALF_BLOCK_ENCRYPTED = 4243
        const val HALF_BLOCK_BROADCAST = 424242
        const val HALF_BLOCK_BROADCAST_ENCRYPTED = 424243
        const val BLOCK_UNENCRYPTED = 35007
        const val BLOCK_ENCRYPTED = 350072

    }

    init {
        messageHandlers[msgIdFixer(MessageId.HALF_BLOCK)] = ::onHalfBlockPacket
        messageHandlers[msgIdFixer(MessageId.HALF_BLOCK_BROADCAST)] = ::onHalfBlockBroadcastPacket
        messageHandlers[msgIdFixer(MessageId.HALF_BLOCK_ENCRYPTED)] = ::onHalfBlockPacket
        messageHandlers[msgIdFixer(MessageId.HALF_BLOCK_BROADCAST_ENCRYPTED)] = ::onHalfBlockBroadcastPacket
    }

    fun sendTransaction(block: TrustChainBlock, peer: Peer? = null, encrypt: Boolean = false) {
        println("sending block...")

        if (peer != null) {
            sendBlockToRecipient(peer, block, encrypt)
        } else {
            sendBlockBroadcast(block, encrypt)
        }
    }

    fun addReceiver(onMessageId: Int, receiver: (Packet) -> Unit) {
        messageHandlers[onMessageId] = receiver
    }

    private fun sendBlockToRecipient(peer: Peer, block: TrustChainBlock, encrypt: Boolean) {
        val payload = HalfBlockPayload.fromHalfBlock(block)

        val data = if (encrypt) {
            serializePacket(MessageId.HALF_BLOCK_ENCRYPTED, payload, false, encrypt = true, recipient = peer)
        } else {
            serializePacket(MessageId.HALF_BLOCK, payload, false)
        }

        send(peer, data)
    }

    private fun sendBlockBroadcast(block: TrustChainBlock, encrypt: Boolean) {
        val payload = HalfBlockBroadcastPayload.fromHalfBlock(block, ttl.toUInt())
        val randomPeers = getPeers().random(broadcastFanOut)
        for (randomPeer in randomPeers) {
            val data = if (encrypt) {
                serializePacket(MessageId.HALF_BLOCK_BROADCAST_ENCRYPTED, payload, false, encrypt = true, recipient = randomPeer)
            } else {
                serializePacket(MessageId.HALF_BLOCK_BROADCAST, payload, false)
            }
            send(randomPeer, data)
        }
    }

    private fun onHalfBlockPacket(packet: Packet) {
        println("half block packet received from: " + packet.source.toString())
    }

    private fun onHalfBlockBroadcastPacket(packet: Packet) {
        println("half block packet received from broadcast from: " + packet.source.toString())
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
    private val incomingUnencryptedBlocks = mutableListOf<HalfBlockPayload>()
    private val incomingEncryptedBlocks = mutableListOf<HalfBlockPayload>()
    private val broadcastFanOut = 25

    // ======================== BLOCK CREATION METHODS =========================

    // this method can be used to benchmark unencrypted Basic block creation with the same content and the same addresses
     fun unencryptedBasicSameContent() : Long {
        val type : String = "benchmark"
        val message : ByteArray = "benchmarkMessage".toByteArray()
        val senderPublicKey : ByteArray = "fakeKey".toByteArray()
        val receiverPublicKey : ByteArray = "fakeReceiverKey".toByteArray()
        var startTime = System.nanoTime()

        var blockList : ArrayList<BasicBlock> = ArrayList()

        for (i in 0..1000) {
            blockList.add(BasicBlock(type, message, senderPublicKey, receiverPublicKey, "".toByteArray()))
        }

        println(blockList.size)

        return System.nanoTime() - startTime
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
    // oposed to the previously used BasicBlocks.
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
        val driver: SqlDriver = if(context!=null) {
            AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")
        } else {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        }

        val store = TrustChainSQLiteStore(Database(driver))
        val random = Random()
        val startTime = System.nanoTime()

        txEngineUnderTest.addReceiver(TransactionEngine.MessageId.HALF_BLOCK, ::onUnencryptedRandomIPv8Packet)

        for (i in 0 .. 1000) {
            val message = ByteArray(200)
            random.nextBytes(message)
            val receiverPublicKey = ByteArray(64)
            random.nextBytes(receiverPublicKey)

            val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned", messageList.get(i), key.pub().keyToBin())

            txEngineUnderTest.sendTransaction(blockBuilder.sign(), destinationPeer, encrypt = false)
        }
        return System.nanoTime() - startTime
    }

    fun unencryptedRandomContentSendIPv8Broadcast(key: PrivateKey, context: Context?) : Long {
        val driver: SqlDriver = if(context!=null) {
            AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")
        } else {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        }

        val store = TrustChainSQLiteStore(Database(driver))
        val random = Random()
        val startTime = System.nanoTime()

        txEngineUnderTest.addReceiver(TransactionEngine.MessageId.HALF_BLOCK, ::onUnencryptedRandomIPv8Packet)

        for (i in 0 .. 1000 / min(txEngineUnderTest.getPeers().size, broadcastFanOut)) {
            val message = ByteArray(200)
            random.nextBytes(message)
            val receiverPublicKey = ByteArray(64)
            random.nextBytes(receiverPublicKey)

            val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned", message, key.pub().keyToBin())

            txEngineUnderTest.sendTransaction(blockBuilder.sign(), encrypt = false)
        }
        return System.nanoTime() - startTime
    }

    // This method can be used to benchmark the sending of signed encrypted blocks over ipv8
    fun encryptedRandomContentSendIPv8(key: PrivateKey, context: Context?, destinationPeer: Peer) : Long {
        val driver: SqlDriver = if(context!=null) {
            AndroidSqliteDriver(Database.Schema, context, "detokstrustchain.db")
        } else {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        }
        val store = TrustChainSQLiteStore(Database(driver))
        val random = Random()
        val startTime = System.nanoTime()

        txEngineUnderTest.addReceiver(TransactionEngine.MessageId.HALF_BLOCK_ENCRYPTED, ::onEncryptedRandomIPv8Packet)

        for (i in 0 .. 1000) {
            val message = ByteArray(200)
            random.nextBytes(message)

            val blockBuilder = SimpleBlockBuilder(myPeer, store, "benchmarkTrustchainSigned", message, key.pub().keyToBin())

            txEngineUnderTest.sendTransaction(blockBuilder.sign(), destinationPeer, encrypt = true)
        }
        return System.nanoTime() - startTime
    }

    // ======================== RECEIVERS ================================

    // This method can be used to benchmark the receiving of signed unencrypted blocks over ipv8
    private fun unencryptedRandomReceiveIPv8() : Long {
        val startTime = System.nanoTime()
        while (incomingUnencryptedBlocks.size<=1000) {
            // wait for all 1000 blocks to be received
        }
        return System.nanoTime() - startTime
    }
    // This method can be used to benchmark the receiving of signed encrypted blocks over ipv8
    private fun encryptedRandomReceiveIPv8(key: PrivateKey, context: Context, peer: Peer) : Long {
        val startTime = System.nanoTime()
        println(key)
        println(context.toString())
        println(peer.key)
        while (incomingUnencryptedBlocks.size<=1000) {
            // wait for all 1000 blocks to be received
        }
        return System.nanoTime() - startTime
    }

    // This method can be used to benchmark the receiving of signed unencrypted blocks over ipv8
    private fun onUnencryptedRandomIPv8Packet(packet: Packet) {
        val payload = packet.getPayload(HalfBlockPayload.Deserializer)
        println("received")
        incomingUnencryptedBlocks.add(payload)
    }
    // This method can be used to benchmark the receiving of signed encrypted blocks over ipv8
    private fun onEncryptedRandomIPv8Packet(packet: Packet) {
        val payload = packet.getPayload(HalfBlockPayload.Deserializer)
        println("received")
        incomingEncryptedBlocks.add(payload)
    }

}
