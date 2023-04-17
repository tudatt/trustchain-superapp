package nl.tudelft.trustchain.detoks

import android.util.Log
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockPayload
import nl.tudelft.ipv8.messaging.Packet
import java.util.*

open class TransactionEngine (override val serviceId: String): Community() {

    fun sendTransaction(block: TrustChainBlock,
                        peer: Peer,
                        encrypt: Boolean = false,
                        msgID: Int) {
        Log.d("TransactionEngine",
            "Creating transaction to peer with public key: " + peer.key.pub().toString())
        sendBlockToRecipient(peer, block, encrypt, msgID)
    }

    fun addReceiver(onMessageId: Int, receiver: (Packet) -> Unit) {
        messageHandlers[onMessageId] = receiver
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
}
