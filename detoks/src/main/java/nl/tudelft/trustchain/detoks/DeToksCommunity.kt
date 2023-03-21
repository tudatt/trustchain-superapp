package nl.tudelft.trustchain.detoks

import android.content.Context
import android.util.Log
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Overlay
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.attestation.trustchain.*
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockBroadcastPayload
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockPairBroadcastPayload
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockPairPayload
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockPayload
import nl.tudelft.ipv8.attestation.trustchain.store.TrustChainStore
import nl.tudelft.ipv8.attestation.trustchain.validation.TransactionValidator
import nl.tudelft.ipv8.attestation.trustchain.validation.ValidationResult
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.util.random
import nl.tudelft.ipv8.util.toHex


class DeToksCommunity(
    private val context: Context,
    private val settings: TrustChainSettings,
    private val database: TrustChainStore
) : Community() {

    private val walletManager = WalletManager(context)
    private val visitedPeers = mutableListOf<Peer>()
    private val txValidators: MutableMap<String, TransactionValidator> = mutableMapOf()
    private val listenersMap: MutableMap<String?, MutableList<BlockListener>> = mutableMapOf()
    private val relayedBroadcasts = mutableSetOf<String>()


    init {
        messageHandlers[MESSAGE_TORRENT_ID] = ::onGossip
        messageHandlers[MESSAGE_TRANSACTION_ID] = ::onTransactionMessage
        val listeners = listenersMap["detoks_transaction"] ?: mutableListOf()
        listenersMap["detoks_transaction"] = listeners
        listeners.add(object : BlockListener {
            override fun onBlockReceived(block: TrustChainBlock) {

            }

        })
    }

    private fun onGossip(packet: Packet) {
        val (peer, payload) = packet.getAuthPayload(TorrentMessage.Deserializer)
        val torrentManager = TorrentManager.getInstance(context)
        Log.d(
            "DeToksCommunity",
            "received torrent from ${peer.mid}, address: ${peer.address}, magnet: ${payload.magnet}"
        )
        torrentManager.addTorrent(payload.magnet)
    }

    fun onTransactionMessage(packet: Packet) {
        val (_, payload) = packet.getAuthPayload(TransactionMessage.Deserializer)

        val senderWallet = walletManager.getOrCreateWallet(payload.senderMID)

        if (senderWallet.balance >= payload.amount) {
            senderWallet.balance -= payload.amount
            walletManager.setWalletBalance(payload.senderMID, senderWallet.balance)

            val recipientWallet = walletManager.getOrCreateWallet(payload.recipientMID)
            recipientWallet.balance += payload.amount
            walletManager.setWalletBalance(payload.recipientMID, recipientWallet.balance)

            Log.d(
                "DeToksCommunity",
                "Received ${payload.amount} tokens from ${payload.senderMID}"
            )
        } else {
            Log.d("DeToksCommunity", "Insufficient funds from ${payload.senderMID}!")
        }
    }

    companion object {
        const val MESSAGE_TORRENT_ID = 1
        const val MESSAGE_TRANSACTION_ID = 2

    }

    override val serviceId = "c86a7db45eb3563ae047639817baec4db2bc7c25"


    private fun getTransactionValidator(blockType: String): TransactionValidator? {
        return txValidators[blockType]
    }


    private fun validateBlock(block: TrustChainBlock): ValidationResult {
        var validationResult = block.validate(database)

        if (validationResult !is ValidationResult.Invalid) {
            val validator = getTransactionValidator(block.type)

            if (validator != null) {
                validationResult = validator.validate(block, database)
            }
        }

        return validationResult
    }

    internal fun notifyListeners(block: TrustChainBlock) {
        val universalListeners = listenersMap[null] ?: listOf<BlockListener>()
        for (listener in universalListeners) {
            listener.onBlockReceived(block)
        }

        val listeners = listenersMap[block.type] ?: listOf<BlockListener>()
        for (listener in listeners) {
            listener.onBlockReceived(block)
        }
    }

    private fun createAgreementBlockToTransaction(
        link: TrustChainBlock,
        transaction: TrustChainTransaction
    ): TrustChainBlock {
        assert(
            link.publicKey.contentEquals(myPeer.publicKey.keyToBin()) || link.publicKey.contentEquals(
                ANY_COUNTERPARTY_PK
            )
        ) {
            "cant sign block not addressed to me"
        }

        assert(link.linkSequenceNumber == UNKNOWN_SEQ) {
            "Cannot counter sign block that is not a request"
        }

        val block = AgreementBlockBuilder(myPeer, database, link, transaction).sign()
        onBlockCreated(block)
        if (block.type !in settings.blockTypesBcDisabled) {
            sendBlockPair(link, block)
        }

        return block
    }

    // this listener will create an agreement for the incoming proposal or decide not to do so.
    private fun notifyAgreementListener(block: TrustChainBlock) {
        if (block.isProposal && block.type == "detoks_transaction") {
            // the block is a proposal, accept it when the balances are sufficient.
            val senderWallet: Wallet = walletManager.getOrCreateWallet(block.publicKey.toString())
            val recipientWallet: Wallet = walletManager.getOrCreateWallet(myPeer.mid)
            val message: String = block.transaction["message"] as String
            val deserializer: TransactionMessage.Deserializer = TransactionMessage.Deserializer
            val transactionByteArray = deserializer.deserialize(message.toByteArray(), 0)
            val messageObject: TransactionMessage = transactionByteArray.component1()
            if (messageObject.amount <= senderWallet.balance && messageObject.recipientMID == myPeer.mid) {
                // the sender has sufficient balance, so send the agreement.
                recipientWallet.balance += messageObject.amount
                senderWallet.balance -= messageObject.amount
                createAgreementBlockToTransaction(block, block.transaction)
            }
        }
    }

    fun sendBlockPair(
        block1: TrustChainBlock,
        block2: TrustChainBlock,
        peer: Peer? = null,
        ttl: UInt = 1u
    ) {
        if (peer != null) {
            val payload = HalfBlockPairPayload.fromHalfBlocks(block1, block2)
            logger.debug("-> $payload")
            val packet =
                serializePacket(DetoksTrustChainCommunity.MessageId.HALF_BLOCK_PAIR, payload, false)
            send(peer, packet)
        } else {
            val payload = HalfBlockPairBroadcastPayload.fromHalfBlocks(block1, block2, ttl)
            logger.debug("-> $payload")
            val packet = serializePacket(
                DetoksTrustChainCommunity.MessageId.HALF_BLOCK_PAIR_BROADCAST, payload,
                false
            )
            for (randomPeer in network.getRandomPeers(settings.broadcastFanout)) {
                send(randomPeer, packet)
            }
            relayedBroadcasts.add(block1.blockId)
        }
    }

    private fun validateAndPersistBlock(block: TrustChainBlock): ValidationResult {
        val validationResult = validateBlock(block)

        if (validationResult is ValidationResult.Invalid) {
            println { "Block is invalid: ${validationResult.errors}" }
        } else {
            if (!database.contains(block)) {
                try {
                    println("addBlock " + block.publicKey.toHex() + " " + block.sequenceNumber)
                    database.addBlock(block)
                } catch (e: Exception) {
                    println("Failed to insert block into database")
                }
                // TODO notify all listeners with the appropriate PK so receiver of the agreement
                // and sender of the agreement don't reply to the same message.
                notifyListeners(block)
                notifyAgreementListener(block)
                notifyAgreementReceived(block)
            }
        }
        return validationResult
    }

    // When a proposal is agreed to, the proposal sender processes the agreement here.
    private fun notifyAgreementReceived(block: TrustChainBlock) {
        if (block.linkPublicKey.contentEquals(myPeer.publicKey.keyToBin()) && block.isAgreement) {
            val message: String = block.transaction["message"] as String
            val deserializer: TransactionMessage.Deserializer = TransactionMessage.Deserializer
            val transactionByteArray = deserializer.deserialize(message.toByteArray(), 0)
            val messageObject: TransactionMessage = transactionByteArray.component1()
            if (messageObject.senderMID == myPeer.mid) {
                val senderWallet: Wallet = walletManager.getOrCreateWallet(myPeer.mid)
                senderWallet.balance -= messageObject.amount
            }
        }
    }

    private fun onBlockCreated(block: TrustChainBlock) {
        // Validate and persist
        val validation = validateAndPersistBlock(block)

        println("Signed block, validation result: $validation")

        if (validation !is ValidationResult.PartialNext && validation !is ValidationResult.Valid) {
            throw RuntimeException("Signed block did not validate")
        }

        val peer = network.getVerifiedByPublicKeyBin(block.linkPublicKey)
        if (peer != null) {
            // If there is a counterparty to sign, we send it
            sendBlock(block, peer = peer)
        }
    }

    fun sendBlock(block: TrustChainBlock, peer: Peer? = null, ttl: Int = 1) {
        if (peer != null) {
            logger.debug("Sending block to $peer")
            val payload = HalfBlockPayload.fromHalfBlock(block)
            logger.debug("-> $payload")
            val packet =
                serializePacket(DetoksTrustChainCommunity.MessageId.HALF_BLOCK, payload, false)
            send(peer, packet)
        } else {
            val payload = HalfBlockBroadcastPayload.fromHalfBlock(block, ttl.toUInt())
            logger.debug("-> $payload")
            val packet = serializePacket(
                DetoksTrustChainCommunity.MessageId.HALF_BLOCK_BROADCAST,
                payload,
                false
            )
            val randomPeers = getPeers().random(settings.broadcastFanout)
            for (randomPeer in randomPeers) {
                send(randomPeer, packet)
            }
            relayedBroadcasts.add(block.blockId)
        }
    }


    fun sendTokens(amount: Int, recipientMid: String) {
        val senderWallet = walletManager.getOrCreateWallet(myPeer.mid)

        Log.d("DetoksCommunity", "my wallet ${senderWallet.balance}")

        if (senderWallet.balance >= amount) {
            Log.d("DetoksCommunity", "Sending $amount money to $recipientMid")
            senderWallet.balance -= amount
            walletManager.setWalletBalance(myPeer.mid, senderWallet.balance)

            val recipientWallet = walletManager.getOrCreateWallet(recipientMid)
            recipientWallet.balance += amount
            walletManager.setWalletBalance(recipientMid, recipientWallet.balance)

            for (peer in getPeers()) {
                val packet = serializePacket(
                    MESSAGE_TRANSACTION_ID,
                    TransactionMessage(amount, myPeer.mid, recipientMid)
                )
                send(peer.address, packet)
            }
        } else {
            Log.d("DeToksCommunity", "Insufficient funds!")
        }
    }


    // This method can be used to send tokens and record the transaction in TrustChain. The balance
    // will only be adjusted once the counterparty has agreed to the proposal.
    fun sendTokensTrustChain(amount: Int, recipientMid: String) {
        val senderWallet = walletManager.getOrCreateWallet(myPeer.mid)

        if (senderWallet.balance >= amount) {
            // Create a TransactionMessage that contains the details of the transaction and put it in a map
            // in order to create a TrustChain halfblock
            val transactionMessage =
                TransactionMessage(amount, myPeer.mid, recipientMid)
            val transaction = mapOf("message" to transactionMessage.serialize().toString())
            val block = ProposalBlockBuilder(
                myPeer,
                database,
                "detoks_transaction",
                transaction,
                recipientMid.toByteArray()
            ).sign()
            onBlockCreated(block)
            if (block.type !in settings.blockTypesBcDisabled) {
                sendBlock(block)
            }

        } else {
            println("insufficient balance")
        }
    }

    fun gossipWith(peer: Peer) {
        Log.d("DeToksCommunity", "Gossiping with ${peer.mid}, address: ${peer.address}")
        Log.d(
            "DetoksCommunity",
            "My wallet size: ${walletManager.getOrCreateWallet(myPeer.mid)}"
        )
        Log.d(
            "DetoksCommunity",
            "My peer wallet size: ${walletManager.getOrCreateWallet(peer.mid)}"
        )
        val listOfTorrents = TorrentManager.getInstance(context).getListOfTorrents()
        if (listOfTorrents.isEmpty()) return
        val magnet = listOfTorrents.random().makeMagnetUri()

        val packet = serializePacket(MESSAGE_TORRENT_ID, TorrentMessage(magnet))

        // Send a token only to a new peer
        if (!visitedPeers.contains(peer)) {
            visitedPeers.add(peer)
            sendTokens(1, peer.mid)
        }

        send(peer.address, packet)
    }

    class Factory(
        private val context: Context,
        private val database: TrustChainStore,
        private val settings: TrustChainSettings
    ) : Overlay.Factory<DeToksCommunity>(DeToksCommunity::class.java) {
        override fun create(): DeToksCommunity {
            return DeToksCommunity(context, settings, database)
        }
    }
}

