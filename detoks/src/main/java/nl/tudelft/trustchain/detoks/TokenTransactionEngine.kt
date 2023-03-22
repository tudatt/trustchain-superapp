package nl.tudelft.trustchain.detoks

import kotlinx.coroutines.launch
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.attestation.trustchain.BlockBuilder
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockBroadcastPayload
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockPayload
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.messaging.Address
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.util.random
import java.util.*


interface TransactionEngine {

    fun sendTransaction(blockBuilder: BlockBuilder, peer: Peer? = null)
}

open class TransactionEngineImpl (
    override val serviceId: String
): TransactionEngine, Community(){

    private val broadcastFanOut = 25
    private val ttl = 100

    object MessageId {
        const val HALF_BLOCK = 4242
        const val HALF_BLOCK_BROADCAST = 424242
    }

    init {
        messageHandlers[msgidfixer(MessageId.HALF_BLOCK)] = ::onHalfBlockPacket
        messageHandlers[msgidfixer(MessageId.HALF_BLOCK_BROADCAST)] = ::onHalfBlockBroadcastPacket
    }

    override fun sendTransaction(blockBuilder: BlockBuilder, peer: Peer?) {
        println("sending block...")
        val block = blockBuilder.sign()

        if (peer != null) {
            sendBlockToRecipient(peer, block)
        } else {
            sendBlockBroadcast(block)
        }
    }

    private fun sendBlockToRecipient(peer: Peer, block: TrustChainBlock) {
        val payload = HalfBlockPayload.fromHalfBlock(block)
        val data = serializePacket(MessageId.HALF_BLOCK, payload, false)
        send(peer, data)
    }

    private fun sendBlockBroadcast(block: TrustChainBlock) {
        val payload = HalfBlockBroadcastPayload.fromHalfBlock(block, ttl.toUInt())
        val data = serializePacket(MessageId.HALF_BLOCK_BROADCAST, payload, false)
        val randomPeers = getPeers().random(broadcastFanOut)
        for (randomPeer in randomPeers) {
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

    fun msgidfixer(msgid: Int): Int{
        @Suppress("DEPRECATION")
        return msgid.toChar().toByte().toInt()
    }
}




