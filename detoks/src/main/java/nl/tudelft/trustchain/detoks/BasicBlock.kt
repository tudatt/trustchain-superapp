package nl.tudelft.trustchain.detoks

import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockPayload
import nl.tudelft.ipv8.keyvault.PrivateKey

class BasicBlock (
    val type: String,
    val message: ByteArray,
    val senderPublicKey: ByteArray,
    val receiverPublicKey: ByteArray,
    var signature: ByteArray
    ) {
    // This class represents the basic blocks that we will use in the first several layers of benchmarking.

    fun sign(key: PrivateKey) {
        signature = key.sign(type.toByteArray() + message + senderPublicKey)
    }


}
